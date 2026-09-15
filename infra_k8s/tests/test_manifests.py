"""Static checks for the k8s option. No cluster, no Docker daemon, no network.

Run from the repository root:
    python -B -m unittest discover -s infra_k8s/tests -v

`kubectl kustomize` is used when available; the tests that need it skip when it
is not, so the file-level checks still run anywhere.
"""

import json
import re
import pathlib
import shutil
import subprocess
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
K8S = ROOT / "infra_k8s"
# One overlay: the Compose stack is removed before this is applied, so
# there is no port-sharing arrangement left to represent.
OVERLAYS = ("cutover",)
KUBECTL = shutil.which("kubectl")


def build(overlay):
    # encoding is explicit: the generated ConfigMap carries nginx.conf, whose
    # comments are Korean. On Windows the default is cp949 and decoding fails.
    out = subprocess.run(
        [KUBECTL, "kustomize", str(K8S / "overlays" / overlay)],
        capture_output=True, check=True, encoding="utf-8",
    )
    return [d for d in yaml.safe_load_all(out.stdout) if d]


def by_kind(docs, kind, name):
    for d in docs:
        if d.get("kind") == kind and d["metadata"]["name"] == name:
            return d
    raise AssertionError(f"{kind}/{name} not found")


def containers(workload):
    return workload["spec"]["template"]["spec"]["containers"]


class NginxCopyTest(unittest.TestCase):
    """The edge must serve the audited production config, not a fork of it."""

    @staticmethod
    def _normalised(path):
        # Line endings differ by design and are not drift. infra_k8s/.gitattributes
        # pins this tree to LF because it is consumed on Linux; infra/ has no such
        # rule for .conf, so on Windows that file checks out CRLF. Comparing raw
        # bytes fails on a fresh checkout even when the content is identical.
        return path.read_bytes().replace(b"\r\n", b"\n")

    def test_copy_matches_the_compose_one(self):
        self.assertEqual(
            self._normalised(ROOT / "infra" / "deploy" / "nginx.conf"),
            self._normalised(K8S / "base" / "nginx" / "default.conf"),
            "infra_k8s/base/nginx/default.conf has drifted from "
            "infra/deploy/nginx.conf. Re-copy it; do not edit one side only.",
        )

    def test_the_site_lands_where_nginx_looks_for_it(self):
        """nginx.conf is the audited production file and is not edited here, so
        the image has to put the site at the path it already names. A mismatch
        serves 404 for every page while the pod stays Ready — /healthz never
        touches the document root."""
        conf = (K8S / "base" / "nginx" / "default.conf").read_text(encoding="utf-8")
        root = re.search(r"^\s*root\s+(\S+);", conf, re.M)
        self.assertTrue(root, "nginx.conf no longer sets a document root")
        dockerfile = (K8S / "image" / "web.Dockerfile").read_text(encoding="utf-8")
        copied = re.search(r"^COPY\s+\S+\s+(\S+)\s*$", dockerfile, re.M)
        self.assertTrue(copied, "web.Dockerfile no longer copies the site in")
        self.assertEqual(root.group(1), copied.group(1))


class ClickHouseCopyTest(unittest.TestCase):
    """base/clickhouse/ is a copy of api/clickhouse/. Every node-specific value
    in those files is `from_env`, so one set serves both pods and there is no
    reason for the two trees to diverge."""

    COPIED = (
        ("server", "00-cluster.xml"), ("server", "01-keeper.xml"),
        ("server", "02-server.xml"), ("server", "10-macros.xml"),
        ("users", "00-profile.xml"), ("users", "10-mupid-user.xml"),
    )

    def test_copies_match_the_originals(self):
        for folder, name in self.COPIED:
            with self.subTest(file=f"{folder}/{name}"):
                self.assertEqual(
                    NginxCopyTest._normalised(ROOT / "api" / "clickhouse" / folder / name),
                    NginxCopyTest._normalised(K8S / "base" / "clickhouse" / folder / name),
                    f"infra_k8s/base/clickhouse/{folder}/{name} has drifted from "
                    f"api/clickhouse/{folder}/{name}. Re-copy it; do not edit one side.",
                )

    def test_keeper_config_is_the_one_deliberate_exception(self):
        """Keeper is the only file that cannot be copied: raft_configuration
        names the host others dial, which under Compose is a container name and
        here has to be the pod's headless DNS."""
        keeper = (K8S / "base" / "clickhouse" / "keeper" / "keeper_config.xml").read_text(encoding="utf-8")
        self.assertIn("clickhouse-keeper-0.clickhouse-keeper", keeper)
        self.assertNotIn("mupid-dev-ch-keeper", keeper, "copied without fixing the hostname")


class SecretKeyTest(unittest.TestCase):
    """Every referenced Secret key must be one the administrator knows to set."""

    # mupid-secrets is built from the application .env, so its keys have to be
    # ones that file declares. Other Secrets come from somewhere else and are
    # checked by the second test instead.
    FROM_ENV_FILE = "mupid-secrets"

    def test_keys_of_the_env_backed_secret_exist_in_the_env_example(self):
        example = (ROOT / "infra" / "deploy" / ".env.example").read_text(encoding="utf-8")
        declared = {
            line.split("=", 1)[0].strip()
            for line in example.splitlines()
            if "=" in line and not line.lstrip().startswith("#")
        }
        referenced = {
            key for name, key in self._secret_refs_in_base() if name == self.FROM_ENV_FILE
        }
        missing = sorted(referenced - declared)
        self.assertEqual(
            [], missing,
            f"manifests reference Secret keys absent from .env.example: {missing}",
        )

    def test_every_other_secret_has_a_documented_way_to_create_it(self):
        """A Secret nobody is told to create leaves the pod stuck in
        CreateContainerConfigError with no hint of what is missing."""
        runbook = (K8S / "OPERATIONS-K8S.md").read_text(encoding="utf-8")
        others = {
            name for name, _ in self._secret_refs_in_base() if name != self.FROM_ENV_FILE
        }
        for name in sorted(others):
            with self.subTest(secret=name):
                # Not assertIn: its failure message prints the whole runbook.
                self.assertTrue(
                    name in runbook,
                    f"Secret {name} is referenced by a manifest but OPERATIONS-K8S.md "
                    f"never says how to create it",
                )

    def _secret_refs_in_base(self):
        for path in (K8S / "base").glob("*.yaml"):
            for doc in yaml.safe_load_all(path.read_text(encoding="utf-8")):
                yield from self._secret_refs(doc)

    @staticmethod
    def _secret_refs(node):
        """Yields (secret name, key) for every secretKeyRef, and (name, None)
        for volumes and envFrom, which name a Secret without naming a key."""
        if isinstance(node, dict):
            ref = node.get("secretKeyRef")
            if isinstance(ref, dict) and "key" in ref:
                yield ref.get("name"), ref["key"]
            secret = node.get("secret")
            if isinstance(secret, dict) and "secretName" in secret:
                yield secret["secretName"], None
            for value in node.values():
                yield from SecretKeyTest._secret_refs(value)
        elif isinstance(node, list):
            for value in node:
                yield from SecretKeyTest._secret_refs(value)


@unittest.skipUnless(KUBECTL, "kubectl not installed")
class BuildTest(unittest.TestCase):
    def test_every_env_entry_has_only_the_fields_it_should(self):
        """An unquoted comma inside a `{name: X, value: a,b}` flow mapping ends
        the entry, so the value is truncated and the rest becomes a key of its
        own. That is valid YAML and builds without complaint; the apiserver
        rejects it on apply, after everything else in the overlay is created."""
        allowed = {"name", "value", "valueFrom"}
        for overlay in OVERLAYS:
            for doc in build(overlay):
                if doc.get("kind") not in ("Deployment", "StatefulSet"):
                    continue
                for container in containers(doc):
                    for entry in container.get("env", []):
                        with self.subTest(name=doc["metadata"]["name"], env=entry):
                            self.assertIn("name", entry)
                            self.assertEqual(set(), set(entry) - allowed)

    def test_a_pod_that_binds_its_own_dns_name_can_resolve_it(self):
        """A headless Service publishes only ready endpoints by default. A pod
        that binds to its own DNS name at startup then waits for a record that
        waits for the pod, and crashes with `no such host` forever."""
        docs = build("cutover")
        for doc in docs:
            if doc.get("kind") != "StatefulSet":
                continue
            args = " ".join(
                str(a) for c in containers(doc) for a in (c.get("args") or [])
            )
            if "$(POD_NAME)." not in args:
                continue
            governing = doc["spec"]["serviceName"]
            with self.subTest(statefulset=doc["metadata"]["name"]):
                service = by_kind(docs, "Service", governing)
                # Headless is spelled with the literal string "None", not YAML
                # null, so this is a string comparison.
                self.assertEqual("None", service["spec"].get("clusterIP"),
                                 "self-addressing needs a headless Service")
                self.assertTrue(service["spec"].get("publishNotReadyAddresses"))

    def test_exec_probes_say_how_long_they_may_take(self):
        """timeoutSeconds defaults to 1. Anything that starts a JVM or another
        client process exceeds that every time, and the container never reports
        ready no matter how healthy it is."""
        for overlay in OVERLAYS:
            for doc in build(overlay):
                if doc.get("kind") not in ("Deployment", "StatefulSet"):
                    continue
                for container in containers(doc):
                    for kind in ("readinessProbe", "livenessProbe", "startupProbe"):
                        probe = container.get(kind)
                        if not probe or "exec" not in probe:
                            continue
                        with self.subTest(name=doc["metadata"]["name"], probe=kind):
                            self.assertIn("timeoutSeconds", probe)

    def test_the_dbstudio_cluster_agrees_on_the_three_things_it_must(self):
        """Its docs name three values that have to match across nodes, and all
        three fail quietly: a different cluster secret gives the replica a 401,
        a different master key replicates fine but leaves that node unable to
        decrypt DB credentials, and a different app version replicates only
        partly with no error anywhere."""
        docs = build("cutover")
        nodes = [
            d for d in docs
            if d.get("kind") == "StatefulSet"
            and d["metadata"]["name"].startswith("dbstudio")
        ]
        self.assertEqual(2, len(nodes), "expected one master and one replica")

        images = {containers(d)[0]["image"] for d in nodes}
        self.assertEqual(1, len(images), f"version skew between nodes: {images}")

        for key in ("DBSTUDIO_CLUSTER_SECRET", "DBSTUDIO_MASTER_KEY"):
            sources = set()
            for node in nodes:
                entry = next(
                    e for e in containers(node)[0]["env"] if e["name"] == key
                )
                ref = entry.get("valueFrom", {}).get("secretKeyRef")
                with self.subTest(node=node["metadata"]["name"], key=key):
                    # A literal value would be a second place to keep in step.
                    self.assertIsNotNone(ref, "must come from a Secret")
                    sources.add((ref["name"], ref["key"]))
            self.assertEqual(1, len(sources), f"{key} read from {sources}")

        roles = {
            next(e["value"] for e in containers(d)[0]["env"]
                 if e["name"] == "DBSTUDIO_CLUSTER_ROLE")
            for d in nodes
        }
        # Two masters means two truths, and they cannot be merged afterwards.
        self.assertEqual({"master", "replica"}, roles)

    def test_dbstudio_is_reachable_only_from_inside_the_cluster(self):
        """It holds every database credential the team has registered, so it is
        reached through an internal proxy, never a port on the node. The edge is
        the one workload allowed to bind the host."""
        docs = build("cutover")
        for doc in docs:
            name = doc["metadata"]["name"]
            if not name.startswith("dbstudio"):
                continue
            with self.subTest(name=name):
                if doc["kind"] == "Service":
                    self.assertIn(doc["spec"].get("type", "ClusterIP"),
                                  ("ClusterIP", "None"))
                    for port in doc["spec"]["ports"]:
                        self.assertIsNone(port.get("nodePort"))
                if doc["kind"] == "StatefulSet":
                    for container in containers(doc):
                        for port in container.get("ports", []):
                            self.assertIsNone(port.get("hostPort"))

        # nginx.conf is the audited production file and must not learn about it.
        conf = (K8S / "base" / "nginx" / "default.conf").read_text(encoding="utf-8")
        self.assertNotIn("dbstudio", conf)

        # And nothing else grows a host port either: the edge owns 80/443.
        binders = {
            doc["metadata"]["name"] for doc in docs
            if doc.get("kind") in ("Deployment", "StatefulSet")
            and any(p.get("hostPort") for c in containers(doc) for p in c.get("ports", []))
        }
        self.assertEqual({"edge"}, binders)

    def test_the_dbstudio_cluster_secret_never_becomes_an_argument(self):
        """The docs are explicit that it is an environment variable only: an
        argument is visible in the process list, and that one value is enough
        to pull every row in the cluster."""
        text = (K8S / "base" / "dbstudio.yaml").read_text(encoding="utf-8")
        for line in text.splitlines():
            if line.lstrip().startswith("#"):
                continue
            with self.subTest(line=line.strip()[:60]):
                self.assertNotIn("-cluster-secret", line)

    def test_every_overlay_builds(self):
        for overlay in OVERLAYS:
            with self.subTest(overlay=overlay):
                self.assertTrue(build(overlay))

    def test_workloads_are_either_pinned_or_deliberately_spread(self):
        """Two ways a pod lands on the wrong node, and they need different rules.

        Our own images have no registry — each is imported into one node's
        containerd — so a workload running one must be pinned or it fails with
        ErrImageNeverPull. A public image may float, but only where the manifest
        says it means to: unpinned with no anti-affinity is an accident, not a
        decision. storage-volume is the one that spreads."""
        for overlay in OVERLAYS:
            for doc in build(overlay):
                if doc.get("kind") not in ("Deployment", "StatefulSet"):
                    continue
                spec = doc["spec"]["template"]["spec"]
                pinned = "mupid.io/role" in (spec.get("nodeSelector") or {})
                locally_imported = any(
                    c["image"].startswith("mupid-") for c in spec["containers"]
                )
                with self.subTest(overlay=overlay, name=doc["metadata"]["name"]):
                    if locally_imported:
                        self.assertTrue(pinned, "no registry; the image is on one node")
                    elif not pinned:
                        anti = (spec.get("affinity") or {}).get("podAntiAffinity") or {}
                        rule = anti.get("requiredDuringSchedulingIgnoredDuringExecution")
                        self.assertTrue(rule, "neither pinned nor spread")
                        # preferred would let both copies share a node, which
                        # satisfies replication 001 on paper and protects nothing.
                        self.assertEqual("kubernetes.io/hostname", rule[0]["topologyKey"])
                        self.assertEqual(2, doc["spec"]["replicas"])

    def test_every_clickhouse_shard_has_a_pod_and_every_pod_a_shard(self):
        """00-cluster.xml lists the shards; the StatefulSet args map each pod
        ordinal to one. Raising replicas without adding a <shard> would leave a
        pod with no identity, and CH_SHARD empty makes two nodes share a Keeper
        path — the way this breaks silently."""
        cluster = (K8S / "base" / "clickhouse" / "server" / "00-cluster.xml").read_text(encoding="utf-8")
        # <mupid> is the sharded cluster; <mupid_all> replicates one shard.
        shards = cluster.split("<mupid>")[1].split("</mupid>")[0].count("<shard>")
        self.assertGreater(shards, 1, "not a split deployment any more")
        sts = by_kind(build("cutover"), "StatefulSet", "clickhouse")
        self.assertEqual(shards, sts["spec"]["replicas"])
        args = containers(sts)[0]["args"][0]
        for ordinal in range(shards):
            with self.subTest(ordinal=ordinal):
                self.assertIn(f"{ordinal}) export CH_SHARD=", args)

    def test_every_broker_name_the_ddl_uses_resolves(self):
        """init/01-schema.sql fixes kafka_broker_list to literal hostnames. The
        Compose deployment resolved them with extra_hosts; here each one needs a
        Service or the Kafka engine table fails at first consume, long after the
        DDL reported success."""
        ddl = (ROOT / "api" / "clickhouse" / "init" / "01-schema.sql").read_text(encoding="utf-8")
        listed = re.search(r"kafka_broker_list\s*=\s*'([^']+)'", ddl)
        self.assertTrue(listed, "kafka_broker_list is gone from the DDL")
        names = {entry.split(":")[0] for entry in listed.group(1).split(",")}
        services = {d["metadata"]["name"] for d in build("cutover") if d["kind"] == "Service"}
        for name in sorted(names):
            with self.subTest(broker=name):
                self.assertIn(name, services)

    def test_kafka_advertises_an_address_other_pods_can_reach(self):
        """Clients are redirected to the advertised listener after bootstrap, so
        a loopback or node-local address here works from inside the pod and
        nowhere else — including the ClickHouse on the other node."""
        sts = by_kind(build("cutover"), "StatefulSet", "kafka")
        env = {e["name"]: e.get("value") for e in containers(sts)[0]["env"]}
        advertised = env["KAFKA_ADVERTISED_LISTENERS"]
        self.assertNotIn("127.0.0.1", advertised)
        self.assertNotIn("localhost", advertised)
        self.assertIn("kafka-0.kafka", advertised)

    def test_the_receiver_targets_containers_that_exist(self):
        """receive-k8s.py sets an image on a named container of a named
        workload. Renaming either in the manifests leaves a deploy command that
        fails after the image is imported and the release recorded — the worst
        moment to find out."""
        source = (K8S / "deploy" / "receive-k8s.py").read_text(encoding="utf-8")
        block = source.split("COMPONENTS = {", 1)[1].split("}", 1)[0]
        table = re.findall(
            r'"[\w-]+":\s*\("deployment/([\w-]+)",\s*"([\w-]+)",\s*"([\w-]+)"\)', block)
        self.assertTrue(table, "COMPONENTS is no longer a readable table")
        docs = build("cutover")
        for workload, container, repository in table:
            with self.subTest(workload=workload):
                images = {c["name"]: c["image"] for c in containers(by_kind(docs, "Deployment", workload))}
                self.assertIn(container, images)
                self.assertTrue(
                    images[container].startswith(repository + ":"),
                    f"{workload}/{container} runs {images[container]}, not {repository}",
                )

    def test_the_edge_takes_the_production_ports(self):
        """hostPort, not a Service: nginx terminates TLS with certbot's files
        from the host, so it binds the node's 80 and 443 directly. Nothing else
        may hold them — teardown.yml asserts they are free."""
        edge = by_kind(build("cutover"), "Deployment", "edge")
        ports = {p["name"]: p.get("hostPort") for p in containers(edge)[0]["ports"]}
        self.assertEqual({"http": 80, "https": 443}, ports)

    def test_no_pod_mounts_anything_from_the_removed_tree(self):
        """/srv/mupid is deleted by teardown.yml. A pod that mounts a path under
        it does not start — hostPath type: Directory — and a pod that mounts the
        docker root reaches state the cluster does not own."""
        # /srv/mupid/web is named because the site moved into the image.
        forbidden = ("/var/lib/docker", "/srv/mupid/config", "/srv/mupid/web")
        for overlay in OVERLAYS:
            for doc in build(overlay):
                if doc.get("kind") not in ("Deployment", "StatefulSet"):
                    continue
                for volume in doc["spec"]["template"]["spec"].get("volumes", []):
                    path = (volume.get("hostPath") or {}).get("path", "")
                    with self.subTest(overlay=overlay, path=path):
                        self.assertFalse(path.startswith(forbidden))

    def test_host_mounts_from_the_existing_tree_are_read_only(self):
        """/etc/letsencrypt, /srv/mupid/acme and /srv/mupid/web belong to certbot
        and to the Compose deployment. This option only reads them."""
        for overlay in OVERLAYS:
            for doc in build(overlay):
                if doc.get("kind") not in ("Deployment", "StatefulSet"):
                    continue
                spec = doc["spec"]["template"]["spec"]
                host_volumes = {
                    v["name"] for v in spec.get("volumes", []) if "hostPath" in v
                }
                for container in spec["containers"]:
                    for mount in container.get("volumeMounts", []):
                        if mount["name"] not in host_volumes:
                            continue
                        with self.subTest(overlay=overlay, mount=mount["mountPath"]):
                            self.assertTrue(mount.get("readOnly"))

    def test_ai_thread_count_matches_its_cpu_limit(self):
        """torch and numpy size their pools from the host core count, not the
        cgroup quota. Left alone the pod oversubscribes and gets slower.

        Read from the file, not from a build: ai.yaml is out of the current
        cutover and not listed in the kustomization. The guard still has to hold
        for the day it goes back in."""
        docs = list(yaml.safe_load_all((K8S / "base" / "ai.yaml").read_text(encoding="utf-8")))
        ai = containers(next(d for d in docs if d and d["kind"] == "Deployment"))[0]
        env = {e["name"]: e.get("value") for e in ai["env"]}
        limit = ai["resources"]["limits"]["cpu"]
        cores = str(int(limit[:-1]) // 1000 if limit.endswith("m") else int(limit))
        for name in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS"):
            with self.subTest(var=name):
                self.assertEqual(cores, env.get(name))

    def test_requests_fit_in_four_vcpu_per_node(self):
        """Both nodes have 4 vCPU. requests are reservations, so an overshoot
        leaves pods Pending rather than merely busy. See BASELINE.md."""
        budget_millicores = 2000
        inventory = (K8S / "ansible" / "inventory.ini").read_text(encoding="utf-8")
        roles = sorted(set(re.findall(r"node_role=(\S+)", inventory)))
        for overlay in OVERLAYS:
            totals = dict.fromkeys(roles, 0)
            for doc in build(overlay):
                if doc.get("kind") not in ("Deployment", "StatefulSet"):
                    continue
                spec = doc["spec"]["template"]["spec"]
                pinned = (spec.get("nodeSelector") or {}).get("mupid.io/role")
                # A spread workload puts a pod on every node, so its request is
                # charged to each of them rather than divided between them.
                charged = [pinned] if pinned else roles
                for container in spec["containers"]:
                    cpu = container["resources"]["requests"]["cpu"]
                    milli = int(cpu[:-1]) if cpu.endswith("m") else int(float(cpu) * 1000)
                    for role in charged:
                        totals[role] += milli
            for role, milli in totals.items():
                with self.subTest(overlay=overlay, role=role):
                    self.assertLessEqual(milli, budget_millicores, json.dumps(totals))


class AnsibleTest(unittest.TestCase):
    """Ansible is not installed on every machine that runs these tests, so these
    are structural checks on the files rather than a syntax check by ansible."""

    ANSIBLE = K8S / "ansible"

    def _load(self, path):
        return list(yaml.safe_load_all(path.read_text(encoding="utf-8")))

    def test_every_playbook_and_role_parses(self):
        paths = list(self.ANSIBLE.glob("*.yml")) + list(self.ANSIBLE.rglob("roles/**/*.yml"))
        paths += list(self.ANSIBLE.glob("group_vars/*.yml"))
        self.assertGreater(len(paths), 5)
        for path in paths:
            with self.subTest(path=path.name):
                self.assertTrue(self._load(path))

    def test_playbooks_reference_roles_that_exist(self):
        for playbook in self.ANSIBLE.glob("*.yml"):
            if playbook.name == "requirements.yml":
                continue
            for play in self._load(playbook)[0]:
                for role in play.get("roles", []):
                    name = role if isinstance(role, str) else role["role"]
                    with self.subTest(playbook=playbook.name, role=name):
                        self.assertTrue((self.ANSIBLE / "roles" / name / "tasks" / "main.yml").exists())

    def test_inventory_carries_no_addresses_or_key_paths(self):
        """Connection values live in infra_k8s/.env, not in the repository. A
        literal address creeping back in is a value that silently overrides the
        operator's .env."""
        text = (self.ANSIBLE / "inventory.ini").read_text(encoding="utf-8")
        for line in text.splitlines():
            if line.lstrip().startswith("#"):
                continue
            with self.subTest(line=line):
                self.assertNotRegex(line, r"\d+\.\d+\.\d+\.\d+")
                self.assertNotRegex(line, r"=/home/|\.pem")

    def test_inventory_reads_only_keys_that_env_example_documents(self):
        """A lookup for a key absent from .env.example resolves to an empty
        string and fails at run time with nothing to point the operator at."""
        text = (self.ANSIBLE / "inventory.ini").read_text(encoding="utf-8")
        read = set(re.findall(r"lookup\(\s*'env'\s*,\s*'([A-Z0-9_]+)'\s*\)", text))
        self.assertTrue(read, "inventory no longer reads .env")
        example = (K8S / ".env.example").read_text(encoding="utf-8")
        documented = set(re.findall(r"^([A-Z0-9_]+)=", example, re.M))
        self.assertEqual(set(), read - documented)

    def test_env_example_holds_keys_but_no_values(self):
        """It is a list of what to fill in. A value here is mistaken for
        configuration and copied forward long after it stops being true."""
        for line in (K8S / ".env.example").read_text(encoding="utf-8").splitlines():
            if re.match(r"^[A-Z0-9_]+=", line):
                with self.subTest(line=line):
                    self.assertTrue(line.endswith("="), "value committed")

    def test_env_check_asserts_the_advertised_address_is_private(self):
        """This guard used to be static, against addresses written in the
        inventory. The values moved to .env, so the same assertion has to run
        before every play — k3s advertising the public address opens 6443 to
        the internet."""
        tasks = self._load(self.ANSIBLE / "roles" / "env_check" / "tasks" / "main.yml")[0]
        conditions = " ".join(
            str(t["ansible.builtin.assert"]["that"])
            for t in tasks if "ansible.builtin.assert" in t
        )
        self.assertIn("k3s_node_ip is match", conditions)
        # The RFC1918 prefixes, written as a regex, so the dots are escaped.
        for prefix in ("10", "192", "168", "172"):
            with self.subTest(prefix=prefix):
                self.assertIn(prefix, conditions, "private-range check is gone")
        self.assertIn("k3s_node_ip != ansible_host", conditions)

    def test_both_playbooks_run_env_check_before_anything_else(self):
        """An empty value has to stop the run with a message. Without this play
        it surfaces as UNREACHABLE against an empty host."""
        for playbook in ("preflight.yml", "cluster.yml", "teardown.yml"):
            with self.subTest(playbook=playbook):
                first = self._load(self.ANSIBLE / playbook)[0][0]
                self.assertIn("env_check", first["roles"])
                # It dials nothing, so it reports the real problem.
                self.assertEqual("local", first["connection"])
                # Otherwise --tags firewall would skip the check.
                self.assertIn("always", first["tags"])

    def test_firewall_opens_forwarding_on_the_cni_interfaces(self):
        """The hosts default to `deny (routed)`. Without a route rule k3s comes up
        Ready while every pod-to-pod packet is dropped — the least obvious way
        this cluster can fail."""
        tasks = self._load(self.ANSIBLE / "roles" / "firewall" / "tasks" / "main.yml")[0]
        routed = [t for t in tasks if (t.get("community.general.ufw") or {}).get("route")]
        self.assertTrue(routed, "no `ufw route` rule; pod networking will fail silently")

    def test_teardown_refuses_to_run_without_a_confirmation(self):
        """It deletes /srv/mupid and the data volumes. Every play in it has to
        be gated, not just the first — `--tags` or a partial run must not reach
        a destructive task through an ungated play."""
        plays = self._load(self.ANSIBLE / "teardown.yml")[0]
        for play in plays:
            if "env_check" in (play.get("roles") or []):
                continue
            with self.subTest(play=play["name"]):
                guards = [
                    task for task in play["tasks"]
                    if "confirm_destroy == 'DESTROY'" in str(
                        (task.get("ansible.builtin.assert") or {}).get("that"))
                ]
                self.assertTrue(guards, "play has no confirmation guard")
                self.assertIs(play["tasks"][0], guards[0], "the guard is not first")

    def test_system_reserved_covers_every_node_role_in_the_inventory(self):
        """kubelet cannot see the Compose stack or the GitLab Runner. A role with
        no reservation would let the scheduler hand out memory they are using."""
        reserved = self._load(self.ANSIBLE / "group_vars" / "all.yml")[0]["system_reserved"]
        inventory = (self.ANSIBLE / "inventory.ini").read_text(encoding="utf-8")
        for role in re.findall(r"node_role=(\S+)", inventory):
            with self.subTest(role=role):
                self.assertIn(role, reserved)

    def test_manifest_node_selectors_match_the_inventory_roles(self):
        """A nodeSelector naming a role no node carries leaves the pod Pending."""
        inventory = (self.ANSIBLE / "inventory.ini").read_text(encoding="utf-8")
        roles = set(re.findall(r"node_role=(\S+)", inventory))
        for path in (K8S / "base").glob("*.yaml"):
            for doc in yaml.safe_load_all(path.read_text(encoding="utf-8")):
                if not doc or doc.get("kind") not in ("Deployment", "StatefulSet"):
                    continue
                # A spread workload carries no selector on purpose; that it says
                # so is checked by test_workloads_are_either_pinned_or_spread.
                selector = doc["spec"]["template"]["spec"].get("nodeSelector") or {}
                if "mupid.io/role" not in selector:
                    continue
                with self.subTest(workload=doc["metadata"]["name"]):
                    self.assertIn(selector["mupid.io/role"], roles)


class DeployEndpointTest(unittest.TestCase):
    """The deployment endpoint is reachable with a CI key, so its input shapes
    are the security boundary."""

    DEPLOY = K8S / "deploy"

    def _forced_command_pattern(self):
        script = (self.DEPLOY / "ssh-entrypoint-k8s.sh").read_text(encoding="utf-8")
        # The pattern escapes its spaces as `\ `, so it cannot be read with \S+.
        found = re.search(r"\$command =~ (.+?) \]\]", script)
        self.assertTrue(found, "the forced command no longer matches a pattern")
        # bash and python spell this pattern the same way.
        return re.compile(found.group(1))

    def test_the_forced_command_takes_nothing_but_a_versioned_upload(self):
        pattern = self._forced_command_pattern()
        good = "deploy-k8s api " + "a" * 40 + " " + "b" * 64
        self.assertTrue(pattern.match(good))
        for bad in (
            good + "; rm -rf /",           # command injection
            good + " extra",               # trailing argument
            good.replace("api", "shell"),  # component not in the enumeration
            good.replace("a" * 40, "A" * 40),   # uppercase is not hex here
            good.replace("a" * 40, "a" * 39),   # short commit
            good.replace("b" * 64, "b" * 63),   # short checksum
            "deploy-k8s api",
            "bash",
        ):
            with self.subTest(command=bad):
                self.assertIsNone(pattern.match(bad))

    def _components(self):
        source = (self.DEPLOY / "receive-k8s.py").read_text(encoding="utf-8")
        block = source.split("COMPONENTS = {", 1)[1].split("}", 1)[0]
        found = re.findall(
            r'"([\w-]+)":\s*\("([\w/-]+)",\s*"([\w-]+)",\s*"([\w-]+)"\)', block)
        self.assertTrue(found, "COMPONENTS is no longer a readable table")
        return {name: rest for name, *rest in [list(f) for f in found]}

    def test_the_entrypoint_and_the_receiver_agree_on_the_components(self):
        """A component the forced command allows but the receiver does not know
        is a request that gets past the boundary and then fails as a usage
        error; the reverse is an unreachable deployment path."""
        pattern = self._forced_command_pattern()
        allowed = set(re.search(r"\((\w+(?:\|\w+)*)\)", pattern.pattern).group(1).split("|"))
        self.assertEqual(allowed, set(self._components()))

    def test_the_receiver_never_holds_cluster_admin(self):
        """k3s.yaml is cluster-admin. Reading it here would make a stolen deploy
        key a cluster takeover, so the receiver uses the ServiceAccount token
        from rbac.yaml instead. Comments may name the file; code may not."""
        source = (self.DEPLOY / "receive-k8s.py").read_text(encoding="utf-8")
        self.assertRegex(source, r'KUBECONFIG = ROOT / "deploy\.kubeconfig"')
        for number, line in enumerate(source.splitlines(), 1):
            if line.lstrip().startswith("#"):
                continue
            with self.subTest(line=number):
                self.assertNotIn("/etc/rancher", line)

    def test_no_k8s_ci_job_can_deploy_off_protected_master(self):
        """These jobs hold the deployment key. Every rule that lets one run has
        to pin the branch, require the ref to be protected, and require the
        deployment switch — otherwise a feature branch, or an unprotected ref
        with the same name, reaches production."""
        jobs = yaml.safe_load((K8S / "ci" / "k8s-release.yml").read_text(encoding="utf-8"))
        checked = 0
        for name, body in jobs.items():
            if not isinstance(body, dict) or "rules" not in body:
                continue
            for rule in body["rules"]:
                condition = rule.get("if", "")
                with self.subTest(job=name, rule=condition[:50]):
                    self.assertIn('$CI_COMMIT_BRANCH == "master"', condition)
                    self.assertIn('$CI_COMMIT_REF_PROTECTED == "true"', condition)
                    self.assertIn('$MUPID_DEPLOY_ENABLED == "true"', condition)
                checked += 1
        self.assertTrue(checked, "no rules found; the file shape changed")

    def test_the_k8s_jobs_still_have_the_verify_jobs_they_need(self):
        """backend-verify and frontend-verify live in infra/ci/. Dropping those
        includes with the Compose deployment would leave `needs` pointing at
        jobs that no longer exist, and the pipeline fails to generate."""
        root = yaml.safe_load((ROOT / ".gitlab-ci.yml").read_text(encoding="utf-8"))
        included = {
            entry["local"] for entry in root["include"] if isinstance(entry, dict)
        }
        defined = set()
        for path in included:
            defined |= {
                key for key in yaml.safe_load((ROOT / path).read_text(encoding="utf-8"))
                if not key.startswith(".")
            }
        jobs = yaml.safe_load((K8S / "ci" / "k8s-release.yml").read_text(encoding="utf-8"))
        for name, body in jobs.items():
            if not isinstance(body, dict):
                continue
            for need in body.get("needs", []):
                job = need["job"] if isinstance(need, dict) else need
                if isinstance(need, dict) and need.get("optional"):
                    continue
                with self.subTest(job=name, needs=job):
                    self.assertIn(job, defined)

    def test_the_k8s_ci_uses_its_own_credential(self):
        """Sharing the Compose deploy key would let either path drive the
        other, which is the thing two accounts were for."""
        script = (K8S / "ci" / "deploy-k8s.sh").read_text(encoding="utf-8")
        self.assertIn("MUPID_DEPLOY_K8S_SSH_KEY", script)
        self.assertNotRegex(script, r"MUPID_DEPLOY_SSH_KEY\b")
        self.assertIn("mupid-deploy-k8s@", script)

    def test_the_deploy_role_cannot_create_or_delete_anything(self):
        """The receiver only ever changes an image tag. Anything wider is reach
        the endpoint does not need and an attacker would."""
        docs = [d for d in yaml.safe_load_all(
            (self.DEPLOY / "rbac.yaml").read_text(encoding="utf-8")) if d]
        role = next(d for d in docs if d["kind"] == "Role")
        for rule in role["rules"]:
            with self.subTest(resources=rule["resources"]):
                self.assertNotIn("secrets", rule["resources"])
                for verb in ("create", "delete", "deletecollection", "*"):
                    self.assertNotIn(verb, rule["verbs"])
            self.assertNotIn("*", rule["apiGroups"])


if __name__ == "__main__":
    unittest.main()
