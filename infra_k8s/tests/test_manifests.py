"""Static checks for the k8s option. No cluster, no Docker daemon, no network.

Run from the repository root:
    python -B -m unittest discover -s infra_k8s/tests -v

`kubectl kustomize` is used when available; the tests that need it skip when it
is not, so the file-level checks still run anywhere.
"""

import json
import os
import re
import pathlib
import shutil
import subprocess
import tempfile
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


class SchemaCheckTest(unittest.TestCase):
    """schema-check.py 는 정규식으로 erd.sql 과 엔티티를 읽는다. 어느 쪽 형식이
    바뀌어 파서가 조용히 아무것도 못 찾게 되면, 차이가 있어도 '차이 없음' 을
    보고한다 — 도구가 있는데 못 잡는 것이 없는 것보다 나쁘다."""

    def setUp(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "schema_check", K8S / "deploy" / "schema-check.py")
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def test_it_reads_the_repository_erd(self):
        tables = self.module.parse_erd(self.module.ERD)
        self.assertGreater(len(tables), 20, "erd.sql 에서 테이블을 거의 못 찾았다")
        for name, columns in tables.items():
            with self.subTest(table=name):
                self.assertTrue(columns, f"{name} 의 컬럼을 하나도 읽지 못했다")

    def test_it_reads_the_entities(self):
        entities = self.module.parse_entities(self.module.ENTITIES)
        self.assertGreater(len(entities), 20, "@Table 엔티티를 거의 못 찾았다")

    def test_type_comparison_survives_formatting_but_not_a_real_change(self):
        """ERD 는 `ENUM('A', 'B')`, MySQL 은 `enum('A','B')` 로 준다. 서식 차이는
        같게 보고, 값이 하나라도 다르면 다르게 봐야 한다 — MUBTI 가 빠졌던 것이
        이 비교로 걸리는 종류다."""
        same = ("ENUM('A', 'B')", "enum('A','B')")
        self.assertEqual(*[self.module.normalise(v) for v in same])
        differ = ("ENUM('A', 'B')", "enum('A','B','C')")
        self.assertNotEqual(*[self.module.normalise(v) for v in differ])
        self.assertNotEqual(self.module.normalise("VARCHAR(50)"),
                            self.module.normalise("varchar(64)"))


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

    def test_no_k8s_job_uploads_an_image_as_an_artifact(self):
        """The API tar is ~220MB and the coordinator answers 413 Request Entity
        Too Large, which fails the job after the build already succeeded. Build
        and transmit belong in one job; the image goes straight to the server."""
        jobs = yaml.safe_load((K8S / "ci" / "k8s-release.yml").read_text(encoding="utf-8"))
        for name, body in jobs.items():
            if not isinstance(body, dict):
                continue
            paths = (body.get("artifacts") or {}).get("paths", [])
            for path in paths:
                with self.subTest(job=name, path=path):
                    self.assertNotIn(".release", path)

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


class WebImageBuildTest(unittest.TestCase):
    """The edge image compiles the frontend from source inside the image build,
    the way the api image compiles the jar. Nothing travels as an artifact.

    The pieces are split across three files that only agree by convention, so
    each seam gets a check here rather than in a 90-minute deploy job."""

    CI = K8S / "ci"
    IMAGE = K8S / "image"

    def test_the_rewrite_matches_a_line_the_runtime_dockerfile_actually_has(self):
        """package-web.sh appends web.Dockerfile and rewrites its COPY to pull
        from the builder stage. If either side is reworded the sed silently
        matches nothing, and the build then copies a dist/client that is not in
        the context. The script's own `grep -q` catches that, but only after
        npm ci has already run."""
        script = (self.CI / "package-web.sh").read_text(encoding="utf-8")
        found = re.search(r"sed 's\|(\^[^|]+)\|([^|]+)\|'", script)
        self.assertTrue(found, "package-web.sh no longer rewrites with sed s|..|..|")
        pattern = re.compile(found.group(1).replace("$", r"\Z"), re.M)
        dockerfile = (self.IMAGE / "web.Dockerfile").read_text(encoding="utf-8")
        self.assertRegex(
            dockerfile, re.compile(found.group(1), re.M),
            "web.Dockerfile has no line for package-web.sh to rewrite",
        )
        self.assertTrue(
            found.group(2).startswith("COPY --from=builder "),
            "the rewrite no longer points at the builder stage",
        )

    def test_the_builder_compiles_on_the_runner_architecture(self):
        """The runner is arm64 and the nodes are x86_64. Without
        --platform=$BUILDPLATFORM this stage runs npm under QEMU to produce
        static files that are the same bytes either way."""
        build = (self.IMAGE / "web.build.Dockerfile").read_text(encoding="utf-8")
        self.assertRegex(
            build, re.compile(r"^FROM --platform=\$BUILDPLATFORM .+ AS builder$", re.M))

    def test_the_runtime_stage_has_no_run(self):
        """That stage is the one built for linux/amd64. A RUN there is the
        emulated one."""
        runtime = (self.IMAGE / "web.Dockerfile").read_text(encoding="utf-8")
        body = [l for l in runtime.splitlines() if not l.lstrip().startswith("#")]
        self.assertFalse([l for l in body if l.startswith("RUN ")])

    def test_the_builder_asks_for_the_static_export(self):
        """Without MUPID_DEPLOY_TARGET the build goes SSR: it writes dist/server
        and never creates dist/client/index.html. The image would then be built
        from an empty document root. frontend-verify sets the same value."""
        build = (self.IMAGE / "web.build.Dockerfile").read_text(encoding="utf-8")
        self.assertRegex(build, r"MUPID_DEPLOY_TARGET=ec2")
        verify = yaml.safe_load(
            (ROOT / "infra" / "ci" / "frontend.yml").read_text(encoding="utf-8"))
        self.assertEqual(
            verify["frontend-verify"]["variables"]["MUPID_DEPLOY_TARGET"], "ec2",
            "frontend-verify and the image build must build the same thing",
        )

    def test_the_frontend_build_is_not_shipped_as_an_artifact(self):
        """It used to be: frontend-verify uploaded dist/client and the deploy
        job downloaded it. That sends the deployed bytes on a round trip
        through the coordinator. The jar never worked that way."""
        verify = yaml.safe_load(
            (ROOT / "infra" / "ci" / "frontend.yml").read_text(encoding="utf-8"))
        for name, body in verify.items():
            if not isinstance(body, dict):
                continue
            with self.subTest(job=name):
                self.assertIsNone(
                    body.get("artifacts"),
                    "the frontend build is an artifact again",
                )
        jobs = yaml.safe_load((self.CI / "k8s-release.yml").read_text(encoding="utf-8"))
        for need in jobs["k8s-deploy-web"]["needs"]:
            with self.subTest(need=need["job"]):
                self.assertFalse(
                    need.get("artifacts", False),
                    "k8s-deploy-web pulls artifacts again",
                )


class LabelRoutingTest(unittest.TestCase):
    """MR 파이프라인은 영역 라벨이 고른다. 라벨이 없으면 require-area-label 이
    실패해 머지를 막는다.

    이 규칙이 조용히 깨지는 방식은 하나다 — verify job 하나에서 라벨 규칙이
    빠지면 그 job 만 `changes:` 로 되돌아간다. 그러면 라벨 없는 MR 이 그 job
    하나만 돌고 초록불이 된다. 여기서 네 job 을 한꺼번에 본다."""

    AREAS = ("Frontend", "Backend", "AI", "Data")
    # job 이름 -> 그 job 을 켜는 라벨
    ROUTES = {
        "frontend-verify": "Frontend",
        "backend-verify": "Backend",
        "ai-server-verify": "AI",
        "data-verify": "Data",
    }
    CI = ROOT / "infra" / "ci"

    def _jobs(self):
        found = {}
        for path in self.CI.glob("*.yml"):
            loaded = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
            for name, body in loaded.items():
                if isinstance(body, dict) and not name.startswith("."):
                    found[name] = body
        return found

    def test_every_area_job_is_gated_on_its_label(self):
        """라벨 규칙이 먼저 오고, 바로 다음에 '나머지 MR 은 never' 가 와야 한다.
        순서가 뒤집히면 라벨이 붙어도 job 이 돌지 않는다 — rules 는 처음
        맞는 것에서 멈춘다."""
        jobs = self._jobs()
        for name, label in self.ROUTES.items():
            with self.subTest(job=name):
                self.assertIn(name, jobs, f"{name} 이 infra/ci 에 없다")
                rules = jobs[name]["rules"]
                first = str(rules[0].get("if", ""))
                self.assertIn('$CI_PIPELINE_SOURCE == "merge_request_event"', first)
                self.assertRegex(
                    first,
                    re.compile(
                        r"CI_MERGE_REQUEST_LABELS =~ /\(\^\|,\)\\s\*"
                        + label + r"\\s\*\(,\|\$\)/"),
                    f"{name} 의 첫 규칙이 {label} 라벨을 보지 않는다",
                )
                self.assertIsNone(rules[0].get("when"))
                second = rules[1]
                self.assertEqual(
                    second.get("if"), '$CI_PIPELINE_SOURCE == "merge_request_event"')
                self.assertEqual(second.get("when"), "never")

    def test_no_area_job_runs_on_an_unlabelled_merge_request(self):
        """라벨 규칙 뒤에 merge_request_event 를 다시 받는 규칙이 남아 있으면
        never 를 지나쳐 돌 수 있다."""
        jobs = self._jobs()
        for name in self.ROUTES:
            for index, rule in enumerate(jobs[name]["rules"][2:], start=2):
                with self.subTest(job=name, rule=index):
                    self.assertNotIn(
                        "merge_request_event", str(rule.get("if", "")),
                        f"{name} 의 {index}번 규칙이 MR 을 다시 받는다",
                    )

    def test_the_guard_covers_exactly_the_four_areas(self):
        """가드가 보는 라벨 목록과 job 이 쓰는 라벨이 어긋나면, 어긋난 라벨은
        가드를 통과하면서 아무 job 도 켜지 않는다 — 검증 없는 초록불이다."""
        guard = self._jobs()["require-area-label"]
        first = str(guard["rules"][0]["if"])
        self.assertEqual(guard["rules"][0].get("when"), "never")
        found = re.search(r"\(([A-Za-z|]+)\)\\s\*\(,\|\$\)", first)
        self.assertTrue(found, "가드가 라벨 목록을 더 이상 열거하지 않는다")
        self.assertEqual(set(found.group(1).split("|")), set(self.AREAS))
        self.assertEqual(set(self.ROUTES.values()), set(self.AREAS))

    def test_the_guard_fails_the_job(self):
        """가드가 돌면서 exit 0 이면 아무것도 막지 못한다."""
        guard = self._jobs()["require-area-label"]
        self.assertIn("exit 1", "\n".join(guard["script"]))

    def _module(self):
        import importlib.util
        if not all(importlib.util.find_spec(m) for m in ("requests", "websocket")):
            self.skipTest("requests/websocket 이 없다")
        spec = importlib.util.spec_from_file_location(
            "deploy_jupyter", self.CI / "deploy-jupyter.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_the_env_file_is_parsed_the_way_a_dotenv_is(self):
        """배포에 필요한 모든 것이 File 형식 변수 하나에 들어간다. 그 파서가
        조용히 틀리면 설정이 반영되지 않은 채로 배포가 성공한다."""
        module = self._module()
        values = module.parse_env(
            "# 주석\n\nexport JUPYTER_USER=j15a204\n"
            "JUPYTER_PASSWORD = \"sp ace\"\nSEGMENT_TIME=10\nREFRESH_TOKEN='tok'\n")
        self.assertEqual(values["JUPYTER_USER"], "j15a204")
        self.assertEqual(values["JUPYTER_PASSWORD"], "sp ace", "따옴표가 남았다")
        self.assertEqual(values["REFRESH_TOKEN"], "tok")
        # 같은 이름이 두 번이면 마지막이 조용히 이긴다 — 어느 값이 반영됐는지
        # 알 수 없으므로 멈춰야 한다.
        with self.assertRaises(SystemExit):
            module.parse_env("A=1\nA=2")

    def test_a_malformed_line_does_not_leak_its_contents(self):
        """이 파일에는 비밀번호와 토큰이 들어 있다. 잘못된 줄이 하필 그 줄일 수
        있으므로 오류 메시지는 줄 번호만 말해야 한다."""
        module = self._module()
        with self.assertRaises(SystemExit) as caught:
            module.parse_env("JUPYTER_PASSWORD my-secret-value")
        self.assertNotIn("my-secret-value", str(caught.exception))

    def test_the_config_is_rendered_from_the_env_file(self):
        """기본값은 저장소의 config.example.json 이고 env 파일이 덮는다.

        타입이 중요하다 — env 값은 전부 문자열인데 서버의 ChunkConfig 는 그냥
        데이터클래스라 segment_time 에 "6" 이 들어가도 받아 주고, ffmpeg 인자를
        만들 때 가서야 깨진다."""
        module = self._module()
        self.assertEqual(module.render_config({})["segment_time"], 6)

        config = module.render_config(
            {"SEGMENT_TIME": "10", "API_BASE_URL": "https://example"})
        self.assertEqual(config["segment_time"], 10, "정수로 바뀌지 않았다")
        self.assertEqual(config["api_base_url"], "https://example")

        with self.assertRaises(SystemExit):
            module.render_config({"SEGMENT_TIME": "여섯"})
        # 오타를 조용히 넘기면 그 설정은 영영 반영되지 않는다.
        with self.assertRaises(SystemExit):
            module.render_config({"API_BASE_URLL": "x"})
        # 접속·제어 이름은 설정 필드가 아니므로 통과해야 한다.
        module.render_config({"JUPYTER_USER": "x", "REFRESH_TOKEN": "y",
                              "RESET_TOKEN": "false"})

    def test_the_example_env_only_names_things_the_deploy_accepts(self):
        """예시에 적힌 이름이 실제로는 거부되면, 그대로 베낀 사람이 배포에서
        막힌다."""
        module = self._module()
        example = (ROOT / "music_preprocessing" / "deploy.env.example").read_text(
            encoding="utf-8")
        names = set()
        for line in example.splitlines():
            line = line.strip().lstrip("#").strip()
            if "=" in line and not line.startswith("─"):
                key = line.split("=", 1)[0].strip()
                if key.isupper() and key.replace("_", "").isalnum():
                    names.add(key)
        self.assertTrue(names, "예시에서 이름을 읽지 못했다")
        allowed = module.CONNECT | module.CONTROL | {
            k.upper() for k in module.defaults()}
        self.assertEqual(names - allowed, set())

    def test_every_rendered_key_is_one_the_server_accepts(self):
        """서버의 ChunkConfig 는 cls(**data) 로 읽는다. 모르는 키가 하나라도
        있으면 TypeError 로 배치가 통째로 죽는다."""
        module = self._module()
        rendered = set(module.render_config({}))
        source = (ROOT / "music_preprocessing" / "src" / "music_preprocessing"
                  / "config.py").read_text(encoding="utf-8")
        fields = set(re.findall(r"^    ([a-z_]+): (?:str|int|bool) =", source, re.M))
        self.assertTrue(fields, "ChunkConfig 의 필드를 읽지 못했다")
        self.assertEqual(
            rendered - fields, set(),
            "config.example.json 에 ChunkConfig 가 모르는 키가 있다",
        )

    def test_the_rotated_token_is_not_overwritten_by_default(self):
        """배치가 refresh_token.txt 를 회전시킨다. 배포마다 CI 의 초기 토큰으로
        되돌리면 이미 무효가 된 토큰을 도로 넣는 셈이라 다음 배치가 인증에
        실패한다. 덮어쓰려면 명시적으로 켜야 한다."""
        script = (self.CI / "deploy-jupyter.py").read_text(encoding="utf-8")
        self.assertIn("RESET_TOKEN", script)
        body = script.split("token_path =", 1)[1].split("print(hub.run", 1)[0]
        self.assertIn("not present or reset", body)
        # 공용 서버라 토큰 파일이 남에게 읽히면 안 된다. contents API 는 644 로
        # 떨어뜨리므로 원격에서 고쳐야 한다.
        self.assertIn("chmod(0o600)", script)

    def test_the_data_deploy_never_ships_server_state(self):
        """공용 서버다. registered.json 을 덮어쓰면 아티스트·앨범·곡이 전부 다시
        만들어지고, refresh_token.txt 는 배치가 회전시키는 자격증명이다.

        config.json 도 이 목록에 남는다. 배포가 config.json 을 쓰기는 하지만
        그것은 CI 변수로 **만들어** 내리는 경로이고, 이 목록이 막는 것은
        저장소 트리에 있는 같은 이름의 파일이 그대로 딸려 올라가는 경우다."""
        script = (self.CI / "deploy-jupyter.py").read_text(encoding="utf-8")
        block = script.split("NEVER = {", 1)[1].split("}", 1)[0]
        for name in ("config.json", "refresh_token.txt", "registered.json",
                     "processed.json", ".python-version"):
            with self.subTest(name=name):
                self.assertIn(name, block)

    def test_the_login_failure_is_not_read_as_success(self):
        """JupyterHub 는 틀린 비밀번호에 401 이 아니라 200 + 로그인 폼을 준다.
        상태 코드만 올려보면 실패한 채로 계속 진행하고, 그 다음 요청들이
        엉뚱한 곳에서 깨진다."""
        script = (self.CI / "deploy-jupyter.py").read_text(encoding="utf-8")
        login = script.split("def _login", 1)[1].split("def ", 1)[0]
        self.assertIn("allow_redirects=False", login)
        self.assertIn("(302, 303)", login)

    def test_the_user_server_xsrf_is_picked_by_path(self):
        """`_xsrf` 쿠키는 허브와 유저 서버 양쪽에서 같은 이름으로 내려온다.
        허브 것을 보내면 업로드가 403 이다."""
        script = (self.CI / "deploy-jupyter.py").read_text(encoding="utf-8")
        self.assertIn('path=f"/user/{self.user}/"', script)
        # 비-GET 요청에 XSRF 헤더가 붙어야 한다. 토큰 인증이면 필요 없던 것이다.
        for call in ("put_file", "ensure_directory", "run"):
            with self.subTest(call=call):
                body = script.split(f"def {call}", 1)[1].split("\n    def ", 1)[0]
                self.assertIn("_headers()", body)

    def test_the_password_variable_can_be_a_file(self):
        """비밀번호가 8자 미만이면 GitLab 이 마스킹을 거부한다. File 형식이면
        환경에 경로만 들어가 마스킹이 필요 없다 — 그래서 값과 경로를 모두 받아야
        한다. 배포 SSH 키가 이미 그 형식이다.

        이건 실제로 돌려 본다. 소스만 보면 '경로였는데 값으로 읽어' 로그인에
        실패하는 경우를 못 잡는다."""
        import importlib.util
        if not all(importlib.util.find_spec(m) for m in ("requests", "websocket")):
            self.skipTest("requests/websocket 이 없다")
        spec = importlib.util.spec_from_file_location(
            "deploy_jupyter", self.CI / "deploy-jupyter.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "pw"
            # File 형식 변수는 내용 끝에 줄바꿈이 붙는 일이 흔하다.
            path.write_text("s3cret\n", encoding="utf-8")
            self.assertEqual(module.read_secret(str(path)), "s3cret")
        # Variable 형식이면 값 그대로다.
        self.assertEqual(module.read_secret("s3cret"), "s3cret")
        # 경로로 해석할 수 없는 문자열이어도 죽지 않는다.
        self.assertEqual(module.read_secret("a\0b/../:*?"), "a\0b/../:*?")

    def test_the_password_never_reaches_the_log(self):
        """CI 변수를 마스킹해도 스크립트가 직접 찍으면 의미가 없다."""
        script = (self.CI / "deploy-jupyter.py").read_text(encoding="utf-8")
        for line in script.splitlines():
            if "print(" in line or "echo" in line:
                with self.subTest(line=line.strip()[:60]):
                    self.assertNotIn("password", line.lower())

    def test_the_crontab_block_is_marked_off(self):
        """마커가 없으면 배포가 남의 크론 항목까지 덮어쓴다."""
        crontab = (ROOT / "music_preprocessing" / "crontab.sh").read_text(encoding="utf-8")
        self.assertIn("# BEGIN music_preprocessing", crontab)
        self.assertIn("# END music_preprocessing", crontab)
        script = (self.CI / "deploy-jupyter.py").read_text(encoding="utf-8")
        self.assertIn('MARKER_BEGIN = "# BEGIN music_preprocessing"', script)
        self.assertIn('MARKER_END = "# END music_preprocessing"', script)
        # 크론은 PATH 가 거의 비어 있다. uv 를 절대경로로 부르지 않으면 조용히
        # 실패한다 — 배포가 {{UV}} 를 실제 경로로 바꾼다.
        self.assertIn("{{UV}}", crontab)
        self.assertIn("{{DIR}}", crontab)


class PrebuiltJobImageTest(unittest.TestCase):
    """의존성을 미리 구워 둔 job 이미지는 레지스트리에 없고 러너 호스트의 도커
    데몬에만 있다. 그래서 두 가지가 맞아야 돈다 — job 이 if-not-present 를
    요청해야 하고, 러너가 그 정책을 허용해야 한다.

    둘 중 하나가 빠지면 도커 허브에서 mupid-ci-* 를 찾다가 실패한다."""

    CI = ROOT / "infra" / "ci"
    RUNNER = ROOT / "infra" / "runner"

    def _jobs(self):
        found = {}
        for path in self.CI.glob("*.yml"):
            for name, body in (yaml.safe_load(path.read_text(encoding="utf-8")) or {}).items():
                if isinstance(body, dict) and not name.startswith("."):
                    found[name] = body
        return found

    def test_locally_built_images_are_not_pulled_from_the_hub(self):
        checked = 0
        for name, body in self._jobs().items():
            image = body.get("image")
            if not isinstance(image, dict) or not str(image.get("name", "")).startswith("mupid-ci-"):
                continue
            with self.subTest(job=name):
                self.assertEqual(
                    image.get("pull_policy"), "if-not-present",
                    f"{name} 이 로컬 이미지를 쓰면서 pull_policy 를 지정하지 않는다",
                )
            checked += 1
        self.assertTrue(checked, "mupid-ci-* 를 쓰는 job 이 하나도 없다")

    def test_the_runner_allows_that_pull_policy(self):
        """configure.py 가 allowed_pull_policies 를 넣지 않으면 러너는
        pull_policy 하나만 허용하고 job 쪽 지정을 거부한다."""
        script = (self.RUNNER / "configure.py").read_text(encoding="utf-8")
        self.assertIn("allowed_pull_policies", script)
        self.assertIn("if-not-present", script)

    def test_every_local_image_has_something_that_builds_it(self):
        """이미지 이름만 바꾸고 빌드 스크립트를 안 고치면, 그 이름은 어디서도
        만들어지지 않아 job 이 영영 못 뜬다."""
        build = (self.RUNNER / "build-images.sh").read_text(encoding="utf-8")
        for name, body in self._jobs().items():
            image = body.get("image")
            if isinstance(image, dict) and str(image.get("name", "")).startswith("mupid-ci-"):
                with self.subTest(job=name):
                    self.assertIn(image["name"], build,
                                  f"{image['name']} 을 굽는 곳이 없다")

    def test_the_job_refuses_a_stale_image(self):
        """의존성을 고치고 이미지를 다시 굽지 않으면 낡은 환경으로 통과한다.
        통과한 코드가 서버에서 처음 깨지는 것이 제일 나쁘다."""
        job = self._jobs()["ai-server-verify"]
        guard = "\n".join(job["before_script"])
        self.assertIn("/opt/mupid/requirements.txt", guard)
        self.assertIn("ai_server/requirements.txt", guard)
        self.assertIn("exit 1", guard)
        # 이미지가 그 사본을 실제로 넣어 두어야 대조가 성립한다.
        dockerfile = (self.RUNNER / "images" / "ai.Dockerfile").read_text(encoding="utf-8")
        self.assertIn("/opt/mupid/requirements.txt", dockerfile)
        # 양쪽 다 줄바꿈을 걷어내야 한다. 윈도우 체크아웃에서 구운 이미지는
        # CRLF 를, CI 체크아웃은 LF 를 들고 있어 내용이 같아도 어긋난다.
        self.assertIn(r"tr -d '\r'", dockerfile)
        self.assertIn(r"tr -d '\r'", guard)

    def test_the_ai_image_carries_git(self):
        """test_env_files_are_ignored 가 git check-ignore 를 부른다. git 이 없으면
        FileNotFoundError 로 죽는다 — 그 테스트의 skip 경로는 '체크아웃이 아님'
        만 받는다. 실제로 job 541874 · 545294 가 그렇게 실패했다."""
        dockerfile = (self.RUNNER / "images" / "ai.Dockerfile").read_text(encoding="utf-8")
        self.assertRegex(dockerfile, r"apt-get install[^\n]*\bgit\b")

    def test_the_ai_job_no_longer_installs_at_runtime(self):
        """이미지에 구워 두고도 설치 단계가 남아 있으면 아낀 것이 없다."""
        job = self._jobs()["ai-server-verify"]
        script = "\n".join(job["script"])
        self.assertNotIn("pip install", script)
        self.assertIsNone(job.get("cache"), "pip 캐시를 다시 나르고 있다")


class CiVariableDocTest(unittest.TestCase):
    """CI 변수 이름이 코드 어딘가에만 있고 아무 데도 적혀 있지 않으면, 새 사람이
    '왜 이 job 이 안 뜨지' 로 반나절을 쓴다. 실제로 이 저장소에서 한 번 있었다 —
    MUPID_JUPYTER_* 를 등록해 두었는데 코드는 MUPID_DATA_ENV 를 보고 있었다.

    살아 있는 파이프라인만 본다. include 에서 빠진 파일(Compose 시절의
    release.yml)은 이름이 남아 있어도 도는 job 이 없다."""

    DOC = ROOT / "infra" / "ci" / "VARIABLES.md"
    # job 안에서 정의해 쓰는 것들이라 등록 대상이 아니다.
    LOCAL = {
        "MUPID_DEPLOY_TARGET",       # frontend.yml 의 variables:
        "MUPID_IMAGE_NAME", "MUPID_BUILD_DIRECTORY", "MUPID_COMMIT_SHA",
        "MUPID_JUPYTER_DIR",         # env 파일 안의 항목
    }

    def _live_files(self):
        root = yaml.safe_load((ROOT / ".gitlab-ci.yml").read_text(encoding="utf-8"))
        files = [ROOT / ".gitlab-ci.yml"]
        for entry in root["include"]:
            files.append(ROOT / entry["local"])
        # 그 job 들이 부르는 스크립트까지 따라간다. 변수는 대개 거기서 읽힌다.
        for path in list(files):
            for name in re.findall(r"(infra[\w/]*/ci/[\w.-]+\.(?:sh|py))",
                                   path.read_text(encoding="utf-8")):
                candidate = ROOT / name
                if candidate.is_file():
                    files.append(candidate)
        return files

    def test_every_variable_the_pipeline_reads_is_written_down(self):
        doc = self.DOC.read_text(encoding="utf-8")
        found = {}
        for path in self._live_files():
            for name in set(re.findall(r"MUPID_[A-Z0-9_]+",
                                       path.read_text(encoding="utf-8"))):
                found.setdefault(name, set()).add(path.name)
        undocumented = sorted(
            name for name in found
            if name not in self.LOCAL and name not in doc
            # env 파일 안의 설정 이름은 deploy.env.example 이 설명한다.
            and not name.startswith("MUPID_PREPROCESSING_")
            # 러너 호스트를 설정하는 값이다. GitLab 에 등록하는 것이 아니라
            # infra/runner/configure.py 에 넘기는 것이라 여기 대상이 아니다.
            and not name.startswith("MUPID_RUNNER_")
        )
        self.assertEqual(
            [], undocumented,
            "infra/ci/VARIABLES.md 에 없는 변수: "
            + ", ".join(f"{n} ({', '.join(sorted(found[n]))})" for n in undocumented),
        )

    def test_the_data_deploy_gate_matches_the_documented_name(self):
        """게이트 이름이 문서와 어긋나면 job 이 조용히 안 뜬다 — 빨간 불이
        아니라 아무 일도 안 일어나는 쪽이라 알아채기 어렵다."""
        data = yaml.safe_load((ROOT / "infra" / "ci" / "data.yml").read_text(encoding="utf-8"))
        gates = {
            name for rule in data["data-deploy"]["rules"]
            for name in re.findall(r"\$(MUPID_[A-Z0-9_]+)", str(rule.get("if", "")))
        }
        self.assertIn("MUPID_DATA_ENV", gates)
        self.assertIn("| `MUPID_DATA_ENV` |", self.DOC.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
