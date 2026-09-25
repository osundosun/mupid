"""Static checks for the k8s option. No cluster, no Docker daemon, no network.

Run from the repository root:
    python -B -m unittest discover -s infra_k8s/tests -v

`kubectl kustomize` is used when available; the tests that need it skip when it
is not, so the file-level checks still run anywhere.
"""

import importlib.util
import json
import os
import re
import pathlib
import shutil
import subprocess
import tempfile
import unittest
import zipfile

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


class NginxSystemUploadTest(unittest.TestCase):
    """모델 가중치 업로드(`/upload/system/`, S15P21A204-453)는 청크와 같은 길이다.

    바이트는 Nginx 가 Filer 로 바로 흘리고 스프링은 auth_request 로 허가만 한다. 여기서
    어긋나면 조용히 새는 것들을 못 박는다: 토큰이 Filer 로 넘어가는 것, PUT 이 아닌 방식이
    스프링의 확인(완료·sha256)을 건너뛰고 Filer 에 닿는 것, `/storage/` 가 밖에 열리는 것.
    개발 템플릿도 같은 모양이어야 로컬에서 확인한 것이 운영과 같다.
    """

    FILES = (
        K8S / "base" / "nginx" / "default.conf",
        ROOT / "api" / "nginx" / "templates" / "default.conf.template",
    )

    @staticmethod
    def _location(conf, head):
        start = conf.index(head)
        # location 블록은 4칸 들여쓴 `}` 로 닫힌다 (안쪽 `{ … }` 는 한 줄이다)
        return conf[start:conf.index("\n    }", start)]

    def test_the_upload_block_asks_spring_and_strips_the_token(self):
        for path in self.FILES:
            with self.subTest(file=path.name):
                conf = path.read_text(encoding="utf-8")
                block = self._location(conf, "location ~ ^/upload/system/")
                self.assertIn("auth_request /internal/system-upload-auth;", block)
                self.assertIn("limit_except PUT { deny all; }", block)
                self.assertIn("proxy_request_buffering off;", block)
                self.assertRegex(block, r"proxy_set_header Authorization (''|\"\");")
                self.assertIn("proxy_pass http://mupid_storage$storage_path;", block)

                auth = self._location(conf, "location = /internal/system-upload-auth {")
                self.assertIn("internal;", auth)
                self.assertIn("proxy_pass_request_body off;", auth)
                self.assertIn("X-Original-Content-Length $content_length", auth)
                self.assertIn(
                    "/api/v1/system/objects/$system_object_id/upload-auth", auth)

    def test_storage_stays_internal(self):
        for path in self.FILES:
            with self.subTest(file=path.name):
                conf = path.read_text(encoding="utf-8")
                block = self._location(conf, "location /storage/ {")
                self.assertRegex(block, r"(?m)^\s*internal;", "/storage/ 가 internal 이 아니면 Filer 가 밖에 열린다")


class ClickHouseCopyTest(unittest.TestCase):
    """base/clickhouse/ is a copy of api/clickhouse/. Every node-specific value
    in those files is `from_env`, so one set serves both pods and there is no
    reason for the two trees to diverge."""

    COPIED = (
        ("server", "00-cluster.xml"), ("server", "01-keeper.xml"),
        ("server", "02-server.xml"), ("server", "03-system-logs.xml"),
        ("server", "10-macros.xml"),
        ("users", "00-profile.xml"), ("users", "10-mupid-user.xml"),
        ("users", "20-serving-user.xml"),
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
        self.assertEqual(1, len(nodes), "expected exactly one dbstudio node in the cluster")

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
        # Allowing only replicas if master is on the edge
        self.assertTrue("replica" in roles)

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

    # 쓰기를 여는 hostPath. **여기 없는 것은 전부 읽기 전용이어야 한다.**
    #
    # `/state` 는 배치가 쌓는 자리다 — 워터마크·이벤트 로그·라벨 중간물·선호 구간.
    # 색인(`/artifacts`)과 **다른 디렉터리**이고, 색인 쪽은 그대로 읽기 전용이다.
    # 둘을 나눈 이유는 색인이 판마다 갈리기 때문이다: 상태를 거기 두면 판을
    # 교체할 때마다 워터마크가 사라져 신호를 처음부터 다시 당긴다.
    WRITABLE_HOST_PATHS = {"/srv/mupid-k8s/ai-state", "/dev/net/tun"}

    def test_host_mounts_from_the_existing_tree_are_read_only(self):
        """/etc/letsencrypt, /srv/mupid/acme and /srv/mupid/web belong to certbot
        and to the Compose deployment. This option only reads them."""
        for overlay in OVERLAYS:
            for doc in build(overlay):
                if doc.get("kind") == "CronJob":
                    spec = doc["spec"]["jobTemplate"]["spec"]["template"]["spec"]
                elif doc.get("kind") in ("Deployment", "StatefulSet"):
                    spec = doc["spec"]["template"]["spec"]
                else:
                    continue
                host_volumes = {
                    v["name"]: v["hostPath"]["path"]
                    for v in spec.get("volumes", []) if "hostPath" in v
                }
                for container in spec["containers"]:
                    for mount in container.get("volumeMounts", []):
                        if mount["name"] not in host_volumes:
                            continue
                        if host_volumes[mount["name"]] in self.WRITABLE_HOST_PATHS:
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
        tables, indexes = self.module.parse_erd(self.module.ERD)
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
        through the coordinator. The jar never worked that way.

        A marker is not a build output — area-gate reads .areas/Frontend to know
        the verification ran, and that is the only thing this job may carry."""
        verify = yaml.safe_load(
            (ROOT / "infra" / "ci" / "frontend.yml").read_text(encoding="utf-8"))
        for name, body in verify.items():
            if not isinstance(body, dict):
                continue
            with self.subTest(job=name):
                carried = (body.get("artifacts") or {}).get("paths") or []
                self.assertEqual(
                    carried, [".areas/Frontend"],
                    "the frontend build output is an artifact again — only the "
                    "area marker may travel",
                )
        jobs = yaml.safe_load((self.CI / "k8s-release.yml").read_text(encoding="utf-8"))
        for need in jobs["k8s-deploy-web"]["needs"]:
            with self.subTest(need=need["job"]):
                self.assertFalse(
                    need.get("artifacts", False),
                    "k8s-deploy-web pulls artifacts again",
                )


class AreaRoutingTest(unittest.TestCase):
    """MR 파이프라인은 영역 라벨이 고른다. 판정과 게이트는 그 위에 얹힌다.

    이 규약이 조용히 깨지는 방식은 여럿이고 전부 초록불로 끝난다.

      1. verify job 하나에서 라벨 규칙이 빠지면 그 job 만 `changes:` 로 돌아간다.
      2. 라벨 목록과 job 목록이 어긋나면 어긋난 라벨은 아무 job 도 켜지 않는다.
      3. 영역 job 이 marker 를 안 남기면 area-gate 가 그 영역을 영영 못 본다.
      4. needs 에서 영역 job 하나가 빠지면 그 marker 가 게이트에 닿지 않는다.

    그래서 **손으로 적은 목록을 쓰지 않는다.** infra/ci/area_diff.py 의 표에서
    유도하고, 규약이 세 곳(표·job·게이트)에서 같은 말을 하는지 본다."""

    CI = ROOT / "infra" / "ci"

    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            "area_diff", cls.CI / "area_diff.py")
        cls.area_diff = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.area_diff)

    def _jobs(self):
        found = {}
        for path in self.CI.glob("*.yml"):
            loaded = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
            for name, body in loaded.items():
                if isinstance(body, dict) and not name.startswith("."):
                    found[name] = body
        return found

    def _routes(self):
        """{영역: job 이름} 을 job 의 rules 에서 **읽어낸다**. 손으로 적지 않는다."""
        pattern = re.compile(
            r"CI_MERGE_REQUEST_LABELS =~ /\(\^\|,\)\\s\*([A-Za-z|]+)\\s\*\(,\|\$\)/")
        routes = {}
        for name, body in self._jobs().items():
            if name == "app-verify":
                continue
            for rule in body.get("rules") or []:
                found = pattern.search(str(rule.get("if", "")))
                if found:
                    routes[found.group(1)] = name
        return routes

    def test_the_area_table_and_the_labelled_jobs_agree(self):
        """표와 job 이 어긋나면 그 영역은 라벨만 있고 job 이 없거나, job 만 있고
        라벨이 없다 — 어느 쪽이든 초록 파이프라인이 나온다."""
        self.assertEqual(self._routes(), {
            "Frontend": "frontend-verify",
            "Backend": "backend-verify",
            "AI": "ai-server-verify",
            "Data": "data-verify",
            "Infra": "infra-verify",
        })
        self.assertEqual(set(self._routes()), set(self.area_diff.AREAS))

    def test_every_area_job_is_gated_on_its_label(self):
        """라벨 규칙이 먼저 오고, 바로 다음에 '나머지 MR 은 never' 가 와야 한다.
        순서가 뒤집히면 라벨이 붙어도 job 이 돌지 않는다 — rules 는 처음
        맞는 것에서 멈춘다."""
        jobs = self._jobs()
        for area, name in self._routes().items():
            with self.subTest(job=name):
                rules = jobs[name]["rules"]
                first = str(rules[0].get("if", ""))
                self.assertIn('$CI_PIPELINE_SOURCE == "merge_request_event"', first)
                self.assertIsNone(rules[0].get("when"))
                second = rules[1]
                self.assertEqual(
                    second.get("if"), '$CI_PIPELINE_SOURCE == "merge_request_event"')
                self.assertEqual(second.get("when"), "never")

    def test_no_area_job_runs_on_an_unlabelled_merge_request(self):
        """라벨 규칙 뒤에 merge_request_event 를 다시 받는 규칙이 남아 있으면
        never 를 지나쳐 돌 수 있다."""
        jobs = self._jobs()
        for name in self._routes().values():
            for index, rule in enumerate(jobs[name]["rules"][2:], start=2):
                with self.subTest(job=name, rule=index):
                    self.assertNotIn(
                        "merge_request_event", str(rule.get("if", "")),
                        f"{name} 의 {index}번 규칙이 MR 을 다시 받는다",
                    )

    def test_the_preflight_fails_a_lacking_label(self):
        """판정은 exit 코드로 끝난다 — area_diff.py 가 1 을 돌려주고 그것이 job 을
        실패시킨다. 구성(문자열)이 아니라 **동작**을 본다."""
        guard = self._jobs()["area-preflight"]
        rules = guard["rules"]
        self.assertEqual(len(rules), 1, "가드가 MR 외의 파이프라인에도 걸린다")
        self.assertEqual(rules[0].get("if"),
                         '$CI_PIPELINE_SOURCE == "merge_request_event"')
        self.assertIn("area_diff.py preflight", "\n".join(str(s) for s in guard["script"]),
                      "가드가 판정을 부르지 않는다 — 실패할 방법이 없다")
        with tempfile.TemporaryDirectory() as tmp:
            code = self.area_diff.main(
                ["preflight", "--paths", "api/x", "--labels", "Data",
                 "--areas-dir", tmp],
                env={"CI_MERGE_REQUEST_IID": "1", "CI_PROJECT_ID": "1"})
        self.assertEqual(code, 1, "모자란 라벨인데 판정이 통과했다")

    def test_every_area_job_records_its_marker(self):
        """marker 가 없으면 area-gate 는 그 영역을 영영 못 본다 — 게이트가
        초록이면서 아무것도 지키지 않게 된다."""
        jobs = self._jobs()
        for area, name in self._routes().items():
            with self.subTest(job=name):
                body = jobs[name]
                script = "\n".join(
                    str(step) for step in body["script"])
                self.assertIn(f".areas/{area}", script,
                              f"{name} 이 {area} marker 를 남기지 않는다")
                paths = (body.get("artifacts") or {}).get("paths") or []
                self.assertIn(f".areas/{area}", paths,
                              f"{name} 의 artifacts 에 {area} marker 가 없다")

    def test_the_gate_is_a_separate_stage_without_needs(self):
        """게이트는 별도 stage 에 있고 needs 가 없어야 한다.

        같은 stage 에 두고 영역 job 을 needs 로 걸면, 영역 job 하나가 실패했을 때
        needs 한 job 이 실패하면 게이트가 skipped 로 간다(GitLab 이 의도된 동작으로
        본다). 그러면 누락 원인을 말해 줄 사람이 사라진다. stage 를 나누고
        when: always 를 주면 영역 job 이 실패해도 게이트가 반드시 돈다.

        needs 가 없으면 앞 stage 의 아티팩트를 기본으로 받으므로 영역 job 목록을
        손으로 적지 않아도 된다 — 영역이 늘어도 이 파일을 고칠 필요가 없다."""
        gate = self._jobs()["area-gate"]
        self.assertEqual(gate["stage"], "area-gate")
        self.assertNotIn("needs", gate,
                         "게이트에 needs 가 생기면 영역 job 실패 시 skipped 로 간다")
        self.assertEqual(gate["rules"][0].get("when"), "always",
                         "앞 stage 가 실패하면 게이트가 돌지 않는다 — 원인을 말할 수 없다")

        root = yaml.safe_load(
            (ROOT / ".gitlab-ci.yml").read_text(encoding="utf-8"))
        self.assertIn("area-gate", root["stages"])
        self.assertLess(root["stages"].index("verify"),
                        root["stages"].index("area-gate"),
                        "게이트 stage 가 verify 앞에 있으면 영역 검증 전에 돈다")

    def test_the_old_gate_is_gone(self):
        """rules 로 라벨을 보던 게이트는 재시도로 복구되지 않는다 — 실측으로
        확인하고 없앴다. 이름만 남아도 사람이 그걸 보고 다시 기대한다."""
        self.assertNotIn("require-area-label", self._jobs())

    def test_the_preflight_and_the_gate_are_wired_into_the_pipeline(self):
        """job 이 파일에 있어도 include 되지 않으면 돌지 않는다."""
        root = yaml.safe_load(
            (ROOT / ".gitlab-ci.yml").read_text(encoding="utf-8"))
        included = {entry["local"] for entry in root["include"]}
        for path in ("infra/ci/labels.yml", "infra/ci/area-gate.yml",
                     "infra/ci/infra.yml"):
            with self.subTest(path=path):
                self.assertIn(path, included)

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

    def test_the_batch_is_driven_by_a_schedule_not_cron(self):
        """그 계정은 cron 스풀에 쓸 권한이 없다 — crontab - 가
        `/var/spool/cron/: mkstemp: Permission denied` 로 실패한다(실측).
        그래서 "언제" 는 GitLab 스케줄이, "무엇을" 은 저장소가 정한다."""
        script = (self.CI / "deploy-jupyter.py").read_text(encoding="utf-8")
        # 배포가 crontab 을 설치하려 들면 매 배포가 실패한다.
        self.assertNotIn('subprocess.run(["crontab", "-"]', script)
        jobs = yaml.safe_load((self.CI / "data.yml").read_text(encoding="utf-8"))
        rules = [str(r.get("if", "")) for r in jobs["data-batch"]["rules"]]
        self.assertTrue(any('"schedule"' in r for r in rules),
                        "스케줄 파이프라인이 data-batch 를 띄우지 않는다")
        runner = ROOT / "music_preprocessing" / "run_batch.sh"
        body = runner.read_text(encoding="utf-8")
        # 순서가 중요하다 — 곡이 있어야 batch 가 스트림을 붙이고, 곡 번호가
        # 있어야 가사를 붙인다.
        order = [body.index(f"run {name}") for name in
                 ("catalog", "covers", "lyrics", "batch")]
        self.assertEqual(order, sorted(order), "배치 순서가 뒤바뀌었다")

    def test_manual_jobs_can_be_started_without_the_web_ui(self):
        """수동 job 이 web 소스만 받으면 UI 에서만 띄울 수 있다. 자동화가
        POST /projects/:id/pipeline 으로 만든 파이프라인은 source 가 api 라
        그 job 이 아예 나타나지 않는다 — 빨간 불이 아니라 '없는' 쪽이라
        한참 헤맨다.

        권한은 같다. 어느 쪽이든 그 ref 에 파이프라인을 만들 수 있어야 하므로
        api 를 받아도 넓어지는 것이 없다."""
        for name in ("data.yml", "backend.yml", "frontend.yml", "ai-server.yml"):
            path = self.CI / name
            with self.subTest(file=name):
                text = path.read_text(encoding="utf-8")
                self.assertNotRegex(
                    text, r'CI_PIPELINE_SOURCE == "web"',
                    f"{name} 이 web 만 받는다. api 도 받아야 한다",
                )
        k8s = (ROOT / "infra_k8s" / "ci" / "k8s-release.yml").read_text(encoding="utf-8")
        self.assertNotRegex(k8s, r'CI_PIPELINE_SOURCE == "web"')

    def test_two_batches_never_overlap(self):
        """같은 곡을 두 프로세스가 동시에 올리면 registered.json 이 서로를
        덮는다."""
        script = (self.CI / "deploy-jupyter.py").read_text(encoding="utf-8")
        self.assertIn("ALREADY", script)
        jobs = yaml.safe_load((self.CI / "data.yml").read_text(encoding="utf-8"))
        for name in ("data-deploy", "data-batch"):
            with self.subTest(job=name):
                self.assertEqual(jobs[name].get("resource_group"), "mupid-jupyter")


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


class AiEndpointNamesTest(unittest.TestCase):
    """LLM 게이트웨이와 추천 서버(ai_server)는 **다른 서버**이고 이름도 다르다.

      MUPID_AI_BASE_URL           대화를 만드는 바깥 LLM
      MUPID_RECOMMENDER_BASE_URL  곡을 고르는 ai_server

    한쪽 이름에 다른 쪽 주소를 넣으면 대화가 추천 서버로 나간다. 실제로
    그랬다 — api/.env.example 에 MUPID_AI_BASE_URL 이 두 번 있었고 뒤의 것이
    http://ai-server:8100 이었으며, 그 값이 운영 ConfigMap 까지 베껴졌다.
    호출은 성공한 것처럼 보이고 대화만 조용히 망가진다."""

    EXAMPLE = ROOT / "api" / ".env.example"

    def _entries(self, text):
        found = {}
        for number, line in enumerate(text.splitlines(), start=1):
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            found.setdefault(line.split("=", 1)[0].strip(), []).append(number)
        return found

    def test_no_name_is_defined_twice_in_the_example(self):
        """`.env` 는 뒤가 이긴다. 같은 이름이 두 번이면 앞의 설명과 다른 값이
        실제로 쓰이고, 읽는 사람은 앞의 것을 믿는다."""
        for name, lines in self._entries(self.EXAMPLE.read_text(encoding="utf-8")).items():
            with self.subTest(name=name):
                self.assertEqual(
                    1, len(lines),
                    f"{name} 이 {lines} 번 줄에 여러 번 있다 — 뒤의 값이 이긴다",
                )

    def test_the_llm_name_never_points_inside_the_cluster(self):
        """MUPID_AI_BASE_URL 은 바깥 LLM 이다. 클러스터 안 주소가 들어가면
        대화가 나가지 못한다."""
        sources = [self.EXAMPLE, K8S / "base" / "config.yaml"]
        for path in sources:
            text = path.read_text(encoding="utf-8")
            for line in text.splitlines():
                stripped = line.strip().lstrip("-").strip()
                if not stripped.startswith("MUPID_AI_BASE_URL"):
                    continue
                value = stripped.split("=", 1)[-1].split(":", 1)[-1].strip() \
                    if "=" in stripped else stripped.split(":", 1)[1].strip()
                if not value:
                    continue
                with self.subTest(file=path.name, value=value):
                    self.assertNotIn("ai-server", value)
                    self.assertNotRegex(value, r"^http://ai\b")

    def test_the_llm_key_actually_reaches_the_container(self):
        """Secret 은 envFrom 으로 통째로 주입하지 않고 이름을 하나씩 적는다.
        그래서 .env 에 키를 넣는 것만으로는 컨테이너에 도달하지 않는다 —
        api.yaml 에 secretKeyRef 가 있어야 한다.

        빠뜨리면 아무 오류 없이 그 설정만 조용히 비어 있다."""
        api = yaml.safe_load_all((K8S / "base" / "api.yaml").read_text(encoding="utf-8"))
        container = next(
            d["spec"]["template"]["spec"]["containers"][0]
            for d in api if d and d.get("kind") == "Deployment")
        named = {e["name"] for e in container.get("env", [])}
        self.assertIn("MUPID_AI_GMS_KEY", named,
                      "LLM 키가 컨테이너에 전달되지 않는다")
        # Secret 을 통째로 주입하면 api 가 쓸 일 없는 비밀(루트 비밀번호 등)까지
        # 들고 있게 된다. 그래서 통째 주입은 하지 않는다.
        for source in container.get("envFrom", []):
            with self.subTest(source=source):
                self.assertNotIn("secretRef", source)

    def test_the_llm_settings_live_in_one_place(self):
        """주소·모델·키를 MUPID_API_ENV 한 묶음으로 관리한다 — 개발 환경의
        api/.env 와 같은 구성이다.

        ConfigMap 에 같은 이름을 다시 넣으면 안 된다. env 로 지정한 값이
        envFrom 을 이기므로 ConfigMap 쪽이 조용히 무시되고, 두 곳을 보는
        사람만 헷갈린다."""
        for doc in yaml.safe_load_all((K8S / "base" / "config.yaml").read_text(encoding="utf-8")):
            if doc and doc.get("kind") == "ConfigMap" and doc["metadata"]["name"] == "api-config":
                data = doc["data"]
                break
        else:
            raise AssertionError("api-config 를 찾지 못했다")
        for name in ("MUPID_AI_BASE_URL", "MUPID_AI_MODEL", "MUPID_AI_GMS_KEY"):
            with self.subTest(name=name):
                self.assertNotIn(name, data)

    def test_the_llm_settings_are_optional_so_the_pod_still_starts(self):
        """Secret 에 없는 키를 optional 없이 참조하면 파드가
        CreateContainerConfigError 로 아예 뜨지 않는다 — 대화 하나 때문에
        서비스 전체가 내려간다."""
        api = yaml.safe_load_all((K8S / "base" / "api.yaml").read_text(encoding="utf-8"))
        container = next(
            d["spec"]["template"]["spec"]["containers"][0]
            for d in api if d and d.get("kind") == "Deployment")
        refs = {e["name"]: (e.get("valueFrom") or {}).get("secretKeyRef", {})
                for e in container.get("env", [])}
        for name in ("MUPID_AI_GMS_KEY", "MUPID_AI_BASE_URL", "MUPID_AI_MODEL"):
            with self.subTest(name=name):
                self.assertIn(name, refs, "컨테이너에 전달되지 않는다")
                self.assertTrue(refs[name].get("optional"),
                                "optional 이 아니면 Secret 에 없을 때 파드가 안 뜬다")

    def test_the_recommender_pair_is_documented_together(self):
        """한쪽만 켜면 추천이 통째로 401 이다. 둘이 같이 보여야 한다."""
        text = self.EXAMPLE.read_text(encoding="utf-8")
        self.assertIn("MUPID_RECOMMENDER_BASE_URL", text)
        self.assertIn("MUPID_RECOMMENDER_INTERNAL_TOKEN", text)
        self.assertIn("MUPID_AI_INTERNAL_TOKEN", text,
                      "추천 서버 쪽 짝 이름이 적혀 있지 않다")


class CiShellScopeTest(unittest.TestCase):
    """GitLab 은 `script:` 의 **각 줄을 별도 셸**로 돌린다.

    그래서 한 줄에서 `export` 한 변수는 다음 줄에 오지 않는다. 이걸 모르면
    스탬프 배선이 조용히 끊긴다 — 실제로 그렇게 실패했다(master, 2026-09-18,
    job 575976). 증상이 나쁜 이유는 **실패 지점이 늦다**는 것이다: APK 를 다
    굽고 서명까지 확인한 뒤(60MB·6분) 업로드 직전 한 줄에서 죽고, job 로그에는
    `exit code 2` 만 남는다.

    여기서는 그 모양을 **구조로** 고정한다: 생성 스크립트와 업로더 스크립트가
    같은 줄에 있어야 한다.
    """

    CI = ROOT / "infra" / "ci"
    K8S_CI = K8S / "ci"

    def _job_script(self, job):
        """job 의 script 줄 목록. GitLab 의 YAML 은 `script` 를 리스트로 준다."""
        config = yaml.safe_load((self.CI / "app.yml").read_text(encoding="utf-8"))
        return config[job]["script"]

    def _lines_with(self, script, needle):
        return [line for line in script if needle in line]

    def test_the_apk_job_exports_the_stamp_from_the_ci_line_not_from_a_child(self):
        """**CI 줄에서 export 해야 한다.** 자식 스크립트의 export 는 부모에 오지 않는다.

        이 함정을 두 번 밟았고 진단도 한 번 틀렸다. 시험도 처음엔 '두 줄이 한 줄에
        있는가' 를 봤는데, 그건 **틀린 진단을 고정한 것**이라 무용했다(한 줄로
        이어도 여전히 죽는다 — 진짜 원인은 `sh build-apk.sh` 가 자식이라는 것이다).

        그래서 보는 것을 바꿨다: **값을 CI 줄에서 만들어 export 하는가.**
        `app-web-release` 가 처음부터 그 모양이라 한 번도 안 깨졌다.
        """
        script = self._job_script("app-build")
        exporter = self._lines_with(script, "export MUPID_NATIVE_BUILD")
        self.assertEqual(1, len(exporter),
                         f"CI 줄에서 MUPID_NATIVE_BUILD 를 export 하는 줄이 하나여야 한다: {exporter}")
        line = exporter[0]
        # 그 줄에서 **값을 만들어** export 한다. 자식의 export 에 기대지 않는다.
        self.assertIn("build-stamp", line,
                      f"CI 줄이 스탬프를 직접 읽어야 한다(자식의 export 는 안 온다): {line!r}")
        self.assertIn("build-apk.sh", line, "스탬프를 만드는 스크립트를 그 줄에서 돌려야 한다")
        # 그리고 업로더가 그 값을 상속받아야 한다 — 같은 셸(앞 줄들의 export)이므로
        # 업로더를 부르는 줄보다 앞에 있으면 된다.
        upload_idx = next(i for i, l in enumerate(script) if "deploy-apk.sh" in l)
        export_idx = next(i for i, l in enumerate(script) if l == line)
        self.assertLess(export_idx, upload_idx,
                        "export 가 업로더 호출보다 앞에 있어야 자식이 상속한다")

    def test_the_stamp_handoff_file_is_written_and_read_at_the_same_path(self):
        """양쪽이 같은 경로를 써야 한다. 한쪽만 바뀌면 배포가 다시 죽는다."""
        build = (self.CI / "build-apk.sh").read_text(encoding="utf-8")
        script = self._job_script("app-build")
        # 쓰는 쪽
        self.assertIn("> .release/app/build-stamp", build,
                      "build-apk.sh 가 스탬프를 파일로 남기지 않는다")
        # 읽는 쪽
        joined = "\n".join(self._lines_with(script, "export MUPID_NATIVE_BUILD"))
        self.assertIn(".release/app/build-stamp", joined,
                      "CI 줄이 그 파일을 읽지 않는다")
        # 값이 숫자인지 양쪽에서 본다 — 아니면 조용히 빈 값이 간다.
        self.assertIn('*[!0-9]*', build)
        self.assertIn("${MUPID_NATIVE_BUILD:?", (self.K8S_CI / "deploy-apk.sh").read_text(encoding="utf-8"))

    def test_the_stamp_really_crosses_that_boundary(self):
        """경계를 넘어야 하는 값이 무엇인지 실제 파일에서 확인한다.

        시험이 '&& 가 있다' 만 보면, 나중에 스크립트가 export 를 그만둬도 통과한다.
        한쪽이 export 하고 다른 쪽이 요구하는지를 함께 본다.
        """
        build = (self.CI / "build-apk.sh").read_text(encoding="utf-8")
        upload = (self.K8S_CI / "deploy-apk.sh").read_text(encoding="utf-8")
        # 왼쪽이 넘기고
        self.assertIn("export MUPID_NATIVE_BUILD", build)
        # 오른쪽이 요구한다
        self.assertIn("${MUPID_NATIVE_BUILD:?", upload)

    def test_the_web_job_does_not_depend_on_a_previous_line(self):
        """웹 갈래는 스탬프를 **그 줄에서** 만들어 넘기므로 이 함정이 없다.

        같은 이유로 이 job 은 나눠도 안전하다 — 그래서 `deploy-web.sh` 는
        스탬프를 환경변수로 받지 않고 아카이브와 `web.json` 에서 읽는다.
        """
        script = self._job_script("app-web-release")
        stamp_line = self._lines_with(script, "build-stamp.sh")
        self.assertEqual(1, len(stamp_line), f"스탬프를 찍는 줄이 하나여야 한다: {stamp_line}")
        self.assertIn("export", stamp_line[0], "찍은 값을 같은 줄에서 export 해야 한다")

    def test_the_uploaders_do_not_hide_the_remote_reason(self):
        """업로더는 원격 stderr 를 그대로 보여주고, 알려진 고장은 이름을 붙인다.

        이것이 없으면 job 로그에 `exit code 1` 만 남는다 — 그 숫자는 어느
        고장인지 말해 주지 않아, 키나 권한을 의심하며 시간을 쓴다. 실제로 그랬다.
        """
        for name, receiver in (("deploy-apk.sh", "mupid-receive-apk"),
                               ("deploy-web.sh", "mupid-receive-web")):
            with self.subTest(uploader=name):
                text = (self.K8S_CI / name).read_text(encoding="utf-8")
                # 원격의 말을 그대로 보여준다.
                self.assertIn('2>"$err"', text)
                self.assertIn('cat "$err" >&2', text)
                # sudoers 누락을 짚어 준다.
                self.assertIn("RECEIVER_NOT_PERMITTED", text)
                self.assertIn("a terminal is required", text)
                self.assertIn(receiver, text)

    def test_the_documented_sudoers_rule_matches_what_the_entrypoint_sudoes(self):
        """수신기를 부르는 곳은 entrypoint 의 `sudo` 뿐이다. 그 목록과 운영 문서가
        적은 sudoers 규칙이 어긋나면, 파일만 넣고 권한을 빠뜨린다 — 그것이 이번에
        라이브에서 실패한 원인이다(`sudo: a password is required`).

        `mupid-receive-index` 는 뺀다. 그 동사는 **다른 노드**로 가고(추천 서버는
        nodeSelector 로 node-b 에 뜬다) 그쪽 sudoers 를 쓴다. 이 저장소의 k8s
        노드 문서에 그 규칙을 요구하면 거짓 실패가 된다.
        """
        entrypoint = (K8S / "deploy" / "ssh-entrypoint-k8s.sh").read_text(encoding="utf-8")
        # `[a-z0-9-]` 여야 한다. `k8s` 의 `8` 을 빼면 `mupid-receive-k` 로 잘려
        # 엉뚱한 이름을 찾는다.
        sudoed = sorted(set(re.findall(r"exec sudo (/usr/local/sbin/mupid-receive-[a-z0-9-]+)",
                                       entrypoint)))
        self.assertTrue(sudoed, "entrypoint 에서 sudo 호출을 찾지 못했다")
        self.assertIn("/usr/local/sbin/mupid-receive-web", sudoed,
                      "upload-web 이 sudo 로 mupid-receive-web 을 부르지 않는다")
        doc = (K8S / "OPERATIONS-K8S.md").read_text(encoding="utf-8")
        self.assertIn("NOPASSWD", doc, "운영 문서에 sudoers 등록 절이 없다")
        for receiver in sudoed:
            if receiver.endswith("mupid-receive-index"):
                continue
            with self.subTest(receiver=receiver):
                self.assertIn(
                    receiver, doc,
                    f"{receiver} 가 운영 문서의 sudoers 절에 없다 — 파일만 넣으면 "
                    "sudo 가 비밀번호를 요구하며 배포가 실패한다",
                )


class ApkDownloadTest(unittest.TestCase):
    """APK 는 웹 이미지에 굽지 않고 노드의 디렉터리에 두고 nginx 가 읽는다.
    그 사이에 맞아야 하는 것이 넷이고, 하나라도 어긋나면 받는 사람이 404 를
    본다 — 올린 쪽은 성공했다고 나온다."""

    DEPLOY = K8S / "deploy"

    def _nginx(self):
        return (K8S / "base" / "nginx" / "default.conf").read_text(encoding="utf-8")

    def _edge(self):
        for doc in yaml.safe_load_all(
                (K8S / "base" / "edge.yaml").read_text(encoding="utf-8")):
            if doc and doc.get("kind") == "Deployment":
                return doc["spec"]["template"]["spec"]
        raise AssertionError("edge Deployment 를 찾지 못했다")

    def test_nginx_serves_the_apk_from_where_the_pod_mounts_it(self):
        """alias 가 가리키는 곳과 파드가 마운트하는 곳이 다르면 404 다."""
        alias = re.search(r"location = /download/mupid\.apk \{[^}]*?alias\s+(\S+);",
                          self._nginx(), re.S)
        self.assertTrue(alias, "nginx 에 /download/mupid.apk 가 없다")
        served = pathlib.PurePosixPath(alias.group(1)).parent.as_posix()
        mounts = {m["mountPath"] for c in self._edge()["containers"]
                  for m in c.get("volumeMounts", [])}
        self.assertIn(served, mounts, f"{served} 를 엣지 파드가 마운트하지 않는다")

    def test_the_mount_comes_from_the_node_directory_the_receiver_writes(self):
        """수신기가 쓰는 곳과 파드가 읽는 곳이 같아야 한다."""
        spec = self._edge()
        volume = next(v for v in spec["volumes"] if v["name"] == "downloads")
        host = volume["hostPath"]["path"]
        receiver = (self.DEPLOY / "receive-apk.py").read_text(encoding="utf-8")
        self.assertIn(f'DOWNLOADS = ROOT / "{pathlib.PurePosixPath(host).name}"', receiver)
        # 한 번도 올리지 않은 클러스터에서도 파드가 떠야 한다. Directory 면
        # 디렉터리가 없을 때 파드가 아예 스케줄되지 않는다.
        self.assertEqual(volume["hostPath"]["type"], "DirectoryOrCreate")

    def test_the_forced_command_takes_nothing_but_a_versioned_apk(self):
        """이 엔드포인트는 CI 키로 닿는다. 모양이 하나라도 헐거우면 그 키를
        훔친 쪽이 다른 일을 할 수 있다.

        **인자가 셋이다.** versionCode 는 앱이 "새 APK 가 있나" 를 판단하는
        유일한 값인데(update.rs), 노드에는 aapt2 가 없고 APK 안 tauri.conf.json
        의 version 은 `0.1.0` 그대로라 바이트에서 읽어 낼 수 없다(실측).
        """
        script = (self.DEPLOY / "ssh-entrypoint-k8s.sh").read_text(encoding="utf-8")
        # 패턴 안에 [0-9a-f] 가 있어 ] 로 끊으면 안 된다. 기존
        # DeployEndpointTest 와 같은 방식으로 게으르게 잡는다.
        found = re.search(r"\$command =~ (\^upload-apk.+?) \]\]", script)
        self.assertTrue(found, "upload-apk 를 받는 규칙이 없다")
        pattern = re.compile(found.group(1))
        good = "upload-apk " + "a" * 40 + " " + "b" * 64 + " 1789725600"
        self.assertTrue(pattern.match(good))
        for bad in (
            good + "; rm -rf /",
            good + " extra",
            good.replace("a" * 40, "A" * 40),
            good.replace("b" * 64, "b" * 63),
            "upload-apk",
            "upload-apk ../../etc/passwd " + "b" * 64,
            # versionCode 가 없으면 앱이 판단할 근거를 잃는다 — 받지 않는다.
            "upload-apk " + "a" * 40 + " " + "b" * 64,
            # 숫자가 아니거나 상한을 넘는 값.
            "upload-apk " + "a" * 40 + " " + "b" * 64 + " abc",
            "upload-apk " + "a" * 40 + " " + "b" * 64 + " 17897256000",
            "upload-apk " + "a" * 40 + " " + "b" * 64 + " -1",
        ):
            with self.subTest(command=bad):
                self.assertIsNone(pattern.match(bad))

    def test_the_sidecar_format_is_what_the_app_parses(self):
        """수신기가 `mupid.apk.sha256` 에 쓰는 한 줄과, 앱이 그것을 읽는 규칙이
        같은 모양이어야 한다. 어긋나면 앱이 체크섬 파일을 못 읽고, 그 실패는
        조용히 "네이티브 갈래를 건너뛴다" 로만 보인다.

        앱 쪽 규칙은 `app/src/update.rs` 의 `parse_checksum_line` 이다:
        첫 토큰이 소문자 16진수 64자, 둘째 토큰이 `mupid-<커밋>.apk`.
        """
        receiver = (self.DEPLOY / "receive-apk.py").read_text(encoding="utf-8")
        # 쓰는 줄.
        self.assertIn('digest.write_text(f"{checksum}  mupid-{release}.apk\\n")', receiver)
        rust = (ROOT / "app" / "src" / "update.rs").read_text(encoding="utf-8")
        # 읽는 규칙. 두 토큰을 그대로 본다.
        self.assertIn("pub fn parse_checksum_line", rust)
        self.assertIn("hex.len() == 64", rust)
        self.assertIn('(b\'a\'..=b\'f\').contains(&byte)', rust)
        self.assertIn('let expected = format!("mupid-{}.apk", manifest.commit);', rust)
        # 앱이 보는 주소가 nginx 가 내려주는 주소와 같아야 한다.
        self.assertIn('pub const NATIVE_CHECKSUM_PATH: &str = "/download/mupid.apk.sha256";', rust)
        self.assertRegex(self._nginx(), r"location = /download/mupid\.apk\.sha256 \{")

    def test_the_receiver_refuses_a_version_code_that_goes_backwards(self):
        """뒤로 가는 versionCode 는 안드로이드가 설치를 거부한다. 한 번 게시되면
        그 APK 를 받은 **모든 기기에서 안내만 뜨고 설치가 실패한다** — 캐시가
        남은 러너가 옛 산출물을 다시 올리는 사고가 실제로 가능한 자리다.

        호출자를 믿지 않고 직전 게시본과 맞대어 보는지 확인한다. 이 검사가
        없으면 수신기는 그냥 덮어쓴다.

        **같은 값은 받는다.** 같은 파이프라인을 재시도하면 스탬프가 커밋 시각에서
        나오므로 같은 versionCode 가 다시 온다 — 그때 막으면 실패한 job 을 다시
        돌릴 수 없다. 앱은 "내 것보다 크지 않으면" 안내하지 않으므로 재촉도 없다.
        """
        receiver = (self.DEPLOY / "receive-apk.py").read_text(encoding="utf-8")
        self.assertIn("def published_version_code", receiver)
        self.assertIn('DOWNLOADS / "native.json"', receiver)
        self.assertIn("version_code < published", receiver)
        self.assertNotIn("version_code <= published", receiver,
                         "같은 값을 막으면 재시도가 안 된다")
        self.assertIn("다운그레이드", receiver)
        # 범위도 본다 — gradle 의 require 와 같은 상한이어야 한다.
        gradle = (ROOT / "app" / "gen" / "android" / "app"
                  / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("2_100_000_000", gradle)
        self.assertIn("VERSION_CODE_MAX = 2_100_000_000", receiver)

    def test_the_receiver_writes_the_manifest_the_app_reads(self):
        """앱은 "새 APK 가 있나" 를 native.json 의 versionCode 로만 판단한다.
        이 파일이 없으면 네이티브 갈래가 통째로 죽는다 — 실제로 그 상태였다
        (라이브 404).

        필드 이름은 `app/src/update.rs` 의 `NativeManifest` 와 1:1 이어야 한다
        (`rename_all = "camelCase"`). 어긋나면 파싱이 실패하고 그 실패는 조용하다.
        """
        receiver = (self.DEPLOY / "receive-apk.py").read_text(encoding="utf-8")
        self.assertIn('"versionCode": version_code', receiver)
        self.assertIn('"builtAt": version_code * 1000', receiver)
        self.assertIn('"apk": "/download/mupid.apk"', receiver)
        # 매니페스트는 **APK 를 다 놓은 뒤에** 쓴다. 순서가 뒤집히면 앱이 아직
        # 없는 파일을 가리키는 매니페스트를 읽는다.
        self.assertLess(receiver.index("pending.replace(DOWNLOADS / \"mupid.apk\")"),
                        receiver.index('pending_manifest.replace(DOWNLOADS / "native.json")'))
        # minSdk 는 호출자가 아니라 APK 바이트에서 읽는다.
        self.assertIn("def min_sdk_of", receiver)

    def test_the_receiver_never_takes_a_path_from_the_caller(self):
        """파일 이름에 들어가는 것은 40자 hex 로 검증한 커밋뿐이다."""
        receiver = (self.DEPLOY / "receive-apk.py").read_text(encoding="utf-8")
        self.assertIn('RELEASE = re.compile(r"^[0-9a-f]{40}$")', receiver)
        self.assertIn("if not RELEASE.match(release)", receiver)
        # 올라온 것이 APK 인지 본다. 공개 URL 로 아무 파일이나 나가면 안 된다.
        self.assertIn("def is_apk", receiver)
        self.assertIn("SIZE_LIMIT", receiver)

    def test_the_apk_build_refuses_an_unsigned_artifact(self):
        """서명 없는 APK 는 안드로이드가 설치를 거부한다. 그것을 받는 사람이
        겪게 두면 안 된다."""
        build = (ROOT / "infra" / "ci" / "build-apk.sh").read_text(encoding="utf-8")
        self.assertIn("! -name '*unsigned*'", build)
        self.assertIn("apksigner", build)

    def test_signing_is_optional_for_a_local_build(self):
        """키스토어가 없다고 로컬에서 `cargo tauri android build` 가 막히면
        안 된다. CI 만 서명한다."""
        gradle = (ROOT / "app" / "gen" / "android" / "app"
                  / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("keystorePropertiesFile.exists()", gradle)
        self.assertIn("signingConfig = signingConfigs.getByName(\"release\")", gradle)
        ignored = (ROOT / "app" / "gen" / "android" / ".gitignore").read_text(encoding="utf-8")
        self.assertIn("keystore.properties", ignored)
        # 키스토어 자체가 커밋되면 서명 키가 저장소에 들어간다.
        self.assertEqual(
            [], [p for p in (ROOT / "app").rglob("*.jks")],
            "키스토어가 작업 트리에 있다",
        )


class WebBundleTest(unittest.TestCase):
    """웹 갈래는 APK 와 **같은 디렉터리**에 얹는다. 새 트리를 만들지 않은 이유는
    마운트·nginx·수신기 셋을 새로 맞춰야 하고, 그 중 하나가 어긋나면 받는 쪽만
    404 를 보기 때문이다 — 올린 쪽은 성공했다고 나온다.

    라이브에서 확인한 상태가 이랬다(2026-09-18):

        /download/mupid.apk.sha256 → 200
        /download/web.json         → 404   ← 앱이 읽어야 하는 매니페스트
        /download/native.json      → 404

    앱은 그 두 주소를 읽도록 짜여 있는데 서버가 만들지도, 내려주지도 않았다.
    이 클래스가 그 셋을 맞대어 본다.
    """

    CI = K8S / "ci"
    DEPLOY = K8S / "deploy"

    def _nginx(self):
        return (K8S / "base" / "nginx" / "default.conf").read_text(encoding="utf-8")

    def _edge_mounts(self):
        for doc in yaml.safe_load_all(
                (K8S / "base" / "edge.yaml").read_text(encoding="utf-8")):
            if doc and doc.get("kind") == "Deployment":
                return {m["mountPath"] for c in doc["spec"]["template"]["spec"]["containers"]
                        for m in c.get("volumeMounts", [])}
        raise AssertionError("edge Deployment 를 찾지 못했다")

    def test_nginx_serves_both_manifests_and_the_bundle(self):
        """앱이 읽는 주소 셋이 nginx 에 있어야 한다. 하나라도 없으면 그 갈래가
        통째로 죽고, 증상은 "업데이트가 안 된다" 뿐이다."""
        conf = self._nginx()
        for path in ("/download/web.json", "/download/native.json"):
            with self.subTest(path=path):
                self.assertRegex(
                    conf, r"location = " + re.escape(path) + r" \{",
                    f"nginx 에 {path} 가 없다")
        # 아카이브는 이름 규칙으로 잡는다. 생성기(web-archive.mjs)가
        # `web-<builtAt>.tar.gz` 를 만들고 매니페스트의 bundle 이 그 이름이다.
        self.assertRegex(conf, r"location ~ \^/download/\(\?<bundle>web-\[0-9\]\+\\\.tar\\\.gz\)\$")
        # alias 가 **이름 있는 캡처 전체**를 써야 한다. regex location 에서
        # nginx 는 매칭된 접두사를 잘라 내므로, `alias /srv/web/downloads/;` 로
        # 두면 디렉터리를 가리키게 되어 404 가 난다.
        self.assertIn("alias /srv/web/downloads/$bundle;", conf)

    def test_the_manifests_are_never_cached_but_the_bundle_may_be(self):
        """매니페스트가 스위치다. 중간 캐시가 값을 붙들면 새 판을 올려도
        업데이트가 영영 안 뜬다.

        반대로 아카이브는 **이름에 타임스탬프가 있어 내용이 바뀌지 않으므로**
        오래 캐시해야 한다 — 그래야 같은 판을 다시 받지 않는다.
        """
        conf = self._nginx()
        for path in ("/download/web.json", "/download/native.json"):
            with self.subTest(path=path):
                block = re.search(
                    r"location = " + re.escape(path) + r" \{.*?\n    \}", conf, re.S)
                self.assertTrue(block, f"{path} 블록을 찾지 못했다")
                self.assertIn("no-store", block.group(0))
        bundle = re.search(r"location ~ \^/download/\(\?<bundle>.+?\n    \}", conf, re.S)
        self.assertTrue(bundle, "아카이브 location 을 찾지 못했다")
        self.assertIn("immutable", bundle.group(0))

    def test_the_bundle_is_served_from_the_directory_the_pod_mounts(self):
        """alias 가 가리키는 곳과 파드가 마운트하는 곳이 다르면 404 다."""
        served = pathlib.PurePosixPath("/srv/web/downloads")
        self.assertIn(served.as_posix(), self._edge_mounts())

    def test_the_receiver_writes_where_nginx_reads(self):
        """수신기의 DOWNLOADS 와 nginx 의 alias, 파드의 마운트가 같은 곳이어야 한다."""
        receiver = (self.DEPLOY / "receive-web.py").read_text(encoding="utf-8")
        self.assertIn('DOWNLOADS = ROOT / "downloads"', receiver)
        self.assertIn("ROOT = pathlib.Path(\"/srv/mupid-k8s\")", receiver)

    def test_the_forced_command_takes_nothing_but_a_versioned_bundle(self):
        script = (self.DEPLOY / "ssh-entrypoint-k8s.sh").read_text(encoding="utf-8")
        found = re.search(r"\$command =~ (\^upload-web.+?) \]\]", script)
        self.assertTrue(found, "upload-web 를 받는 규칙이 없다")
        pattern = re.compile(found.group(1))
        good = "upload-web " + "a" * 40 + " " + "b" * 64
        self.assertTrue(pattern.match(good))
        for bad in (good + "; rm -rf /", good + " extra",
                    good.replace("a" * 40, "A" * 40),
                    good.replace("b" * 64, "b" * 63), "upload-web",
                    "upload-web ../../etc/passwd " + "b" * 64):
            with self.subTest(command=bad):
                self.assertIsNone(pattern.match(bad))

    def test_the_receiver_never_takes_a_path_from_the_caller(self):
        """수신기가 파일 이름을 **전개 결과에서 스스로 만든다.** 클라이언트가
        경로를 정하면 공개 URL 로 아무 파일이나 나가게 만들 수 있다."""
        receiver = (self.DEPLOY / "receive-web.py").read_text(encoding="utf-8")
        self.assertIn('RELEASE = re.compile(r"^[0-9a-f]{40}$")', receiver)
        self.assertIn("if not RELEASE.match(release)", receiver)
        # 이름은 builtAt 에서 나온다 — 요청이 준 이름이 아니다.
        self.assertIn('target = DOWNLOADS / f"web-{built_at}.tar.gz"', receiver)
        # 심볼릭 링크·..·절대경로를 거부한다(receive-front.py 가 정한 규칙).
        self.assertIn("def safe_extract", receiver)
        self.assertIn("not member.isfile() and not member.isdir()", receiver)
        self.assertIn('".." in name.parts', receiver)

    def test_the_manifest_is_written_last(self):
        """게시 순서: 아카이브 먼저, 매니페스트 나중. 반대로 하면 앱이 아직 없는
        파일을 가리키는 매니페스트를 읽는다."""
        receiver = (self.DEPLOY / "receive-web.py").read_text(encoding="utf-8")
        self.assertIn('"bundle": f"/download/web-{built_at}.tar.gz"', receiver)
        self.assertLess(receiver.index("archive.replace(target)"),
                        receiver.index('pending.replace(DOWNLOADS / "web.json")'))

    def test_the_receiver_makes_the_manifest_from_what_it_unpacked(self):
        """**매니페스트를 아카이브에서 만든다.** 스탬프가 세 곳(아카이브 이름 ·
        `app/build.json` · `web.json`)에 흩어져 있으면 하나가 어긋나고, 그 증상은
        "앱이 자기 번들을 계속 다시 받는다" 로만 보인다(설계 12장 10번).

        클라이언트가 보낸 값이 아니라 **전개하면서 센 값**이어야 한다.
        """
        receiver = (self.DEPLOY / "receive-web.py").read_text(encoding="utf-8")
        self.assertIn("def read_built_at", receiver)
        self.assertIn('if not (tree / ENTRY).is_file()', receiver)
        # files·expandedBytes 도 전개 결과에서 온다. 앱이 그 값과 대조한다.
        self.assertIn("files, expanded = safe_extract(archive, tree)", receiver)

    def test_the_receiver_limits_match_the_apps(self):
        """수신기 상한이 앱보다 느슨하면 앱이 거부할 번들을 공개 URL 에 올려 두게
        된다 — 증상은 "업데이트가 안 된다" 뿐이다. 좁으면 앱이 받을 수 있는 것을
        못 올린다. 어느 쪽이든 어긋나면 안 된다.
        """
        receiver = (self.DEPLOY / "receive-web.py").read_text(encoding="utf-8")
        rust = (ROOT / "app" / "src" / "update.rs").read_text(encoding="utf-8")
        for constant, value in (
            ("MAX_ARCHIVE_BYTES", "4 * 1024 * 1024"),
            ("MAX_EXPANDED_BYTES", "24 * 1024 * 1024"),
            ("MAX_FILES", "2048"),
            ("MAX_MEMBER_BYTES", "8 * 1024 * 1024"),
        ):
            with self.subTest(constant=constant):
                self.assertIn(f"{constant} = {value}", receiver,
                              f"receive-web.py 의 {constant} 이 앱과 다르다")
                self.assertIn(value, rust,
                              f"app/src/update.rs 에 {constant} = {value} 가 없다")

    def test_the_schema_matches_the_apps(self):
        """모르는 스키마 값이면 앱이 손대지 않는다 — 아무 일도 일어나지 않는다."""
        for name, path in (("receive-web.py", self.DEPLOY / "receive-web.py"),
                           ("receive-apk.py", self.DEPLOY / "receive-apk.py")):
            with self.subTest(receiver=name):
                self.assertIn("SCHEMA = 1", path.read_text(encoding="utf-8"))
        rust = (ROOT / "app" / "src" / "update.rs").read_text(encoding="utf-8")
        self.assertIn("pub const SCHEMA: u32 = 1;", rust)

    def test_the_uploader_checks_the_edge_before_it_reports_success(self):
        """수신기가 성공했다고 해도 nginx location 이나 마운트가 어긋나 있으면
        앱은 404 를 받는다. 그건 올린 사람이 먼저 겪어야 한다 — deploy-apk.sh 가
        이미 그렇게 한다."""
        uploader = (self.CI / "deploy-web.sh").read_text(encoding="utf-8")
        self.assertIn("/download/web.json", uploader)
        self.assertIn("exit 65", uploader)
        # 아카이브 자체도 받아 본다 — 매니페스트만 맞고 파일이 404 인 경우가 있다.
        self.assertIn('"$base$bundle"', uploader)
        # 스탬프 셋(이름·builtAt·sha256)이 같은지 미리 본다.
        self.assertIn('"web-$built_at.tar.gz"', uploader)
        self.assertIn("web.json 의 sha256 과 아카이브가 다르다", uploader)

    def test_the_uploader_is_reachable_from_the_pipeline_it_documents(self):
        """문서화한 호출(`sh infra_k8s/ci/deploy-web.sh`)이 실제 파일과 맞는지."""
        self.assertTrue((self.CI / "deploy-web.sh").is_file())
        self.assertTrue(os.access(self.CI / "deploy-web.sh", os.R_OK))


class AiServerDeployTest(unittest.TestCase):
    """추천 서버는 **무상태**로 띄운다. 인계 문서는 산출물 513MB 를 /artifacts 로
    마운트하는 전제였는데, 구조를 따져 보니 나눌 수 있었다 — 임베딩은 Pinecone,
    라벨은 ClickHouse(원천이 거기다), 모델 가중치 352KB 는 이미지에.

    파드는 아직 0 이다. 코드가 아직 파일 산출물에 의존해서 지금 띄우면
    /internal/ready 가 503 이다. 배포 경로만 먼저 세워 둔다."""

    AI = K8S / "base" / "ai.yaml"

    def _deployment(self):
        for doc in yaml.safe_load_all(self.AI.read_text(encoding="utf-8")):
            if doc and doc.get("kind") == "Deployment":
                return doc
        raise AssertionError("ai Deployment 를 찾지 못했다")

    def test_the_pod_reads_the_index_read_only(self):
        """색인은 hostPath 로 온다. 앞서 이 시험은 "볼륨이 없다" 를 고정하고
        있었는데, 그 설계는 두 가지 틀린 근거 위에 있었다 — 특징값을 서빙이
        읽지 않는다(읽는다)와 임베딩을 Pinecone 으로 옮긴다(풀이 10,007구간이라
        근거가 없다).

        읽기 전용인 것이 핵심이다. 이 파드는 색인을 만들지 않는다 — 배치가
        만들고 CI 가 올린다. 쓸 수 있게 두면 파드가 노드 디스크를 고칠 수 있고,
        레플리카를 늘렸을 때 서로의 색인을 덮어쓴다."""
        spec = self._deployment()["spec"]["template"]["spec"]
        volume = {v["name"]: v for v in spec["volumes"]}["artifacts"]
        self.assertIn("hostPath", volume)
        mount = {m["name"]: m for m in spec["containers"][0]["volumeMounts"]}
        self.assertTrue(mount["artifacts"]["readOnly"])

    def test_replicas_can_still_be_raised(self):
        """hostPath 를 쓰면서도 레플리카를 늘릴 수 있어야 한다.

        읽기 전용이고 같은 노드에 고정(nodeSelector)이라 여러 파드가 같은
        디렉터리를 함께 읽는다. ReadWriteMany 가 필요해지는 것은 쓰기를 열 때다.
        """
        spec = self._deployment()["spec"]["template"]["spec"]
        self.assertEqual("ai", spec["nodeSelector"]["mupid.io/role"])
        mount = {m["name"]: m for m in spec["containers"][0]["volumeMounts"]}
        self.assertTrue(mount["artifacts"]["readOnly"])

    def test_the_pool_excludes_sources_that_cannot_be_played(self):
        """jamendo 는 실서비스에서 재생되지 않는다. 추천에 섞이면 사용자가 누를 수
        없는 결과가 나간다 — 오류가 아니라 '재생이 안 되는 추천' 이라 화면만 보고는
        무엇이 잘못됐는지 알기 어렵다.

        코드 기본값이 `MUPID_POOL_EXTRA=jamendo` 라 **매니페스트가 덮지 않으면 그 값이
        그대로 나간다.** 실제로 그렇게 나가고 있었다."""
        container = self._deployment()["spec"]["template"]["spec"]["containers"][0]
        env = {e["name"]: e.get("value") for e in container["env"] if "value" in e}
        self.assertEqual("flow", env["MUPID_POOL"])
        self.assertEqual("", env["MUPID_POOL_EXTRA"],
                         "기본값(jamendo)이 새어 나간다")

    def test_the_probes_are_not_swapped(self):
        """/health 는 프로세스 생존만, /internal/ready 는 실제로 추천할 수 있는가를
        본다. /health 를 readiness 로 쓰면 추천이 안 되는 파드로 트래픽이 가고,
        /internal/ready 를 liveness 로 쓰면 준비되기 전에 계속 재시작한다."""
        container = self._deployment()["spec"]["template"]["spec"]["containers"][0]
        self.assertEqual("/health", container["livenessProbe"]["httpGet"]["path"])
        self.assertEqual("/internal/ready", container["readinessProbe"]["httpGet"]["path"])

    def test_the_internal_api_is_not_reachable_from_outside(self):
        """/internal 에는 인증이 없고 Spring 만 부른다는 전제다. 열리면 누구나
        추천 API 를 호출할 수 있다."""
        for doc in yaml.safe_load_all(self.AI.read_text(encoding="utf-8")):
            if doc and doc.get("kind") == "Service":
                with self.subTest(service=doc["metadata"]["name"]):
                    self.assertIn(doc["spec"].get("type", "ClusterIP"), ("ClusterIP",))
        nginx = (K8S / "base" / "nginx" / "default.conf").read_text(encoding="utf-8")
        # 업로드 허가 두 곳은 nginx 자신의 auth_request 서브요청이다 (`internal;`, Spring 으로 간다).
        for own in ("/internal/chunk-upload-auth", "/internal/system-upload-auth"):
            nginx = nginx.replace(own, "")
        self.assertNotIn("/internal/", nginx)

    def test_the_shared_secret_is_optional_so_the_pod_still_starts(self):
        """Secret 에 없는 키를 optional 없이 참조하면 파드가
        CreateContainerConfigError 로 아예 뜨지 않는다."""
        container = self._deployment()["spec"]["template"]["spec"]["containers"][0]
        refs = {e["name"]: (e.get("valueFrom") or {}).get("secretKeyRef", {})
                for e in container["env"] if "valueFrom" in e}
        self.assertTrue(refs["MUPID_AI_INTERNAL_TOKEN"].get("optional"))

    def test_the_deploy_path_knows_the_component(self):
        """매니페스트만 있고 배포 경로가 없으면 올릴 때가 되어서야 처음 돌려
        보게 된다. 컴포넌트는 열거라 세 곳이 맞아야 한다."""
        entry = (K8S / "deploy" / "ssh-entrypoint-k8s.sh").read_text(encoding="utf-8")
        self.assertIn(r"(api|web|ai)", entry)
        receiver = (K8S / "deploy" / "receive-k8s.py").read_text(encoding="utf-8")
        self.assertIn('"ai": ("deployment/ai", "ai", "mupid-ai")', receiver)
        deploy = (K8S / "ci" / "deploy-k8s.sh").read_text(encoding="utf-8")
        self.assertIn("ai)  archive=.release/ai/image.tar.gz", deploy)
        # 매니페스트의 이름과 수신기가 부르는 이름이 같아야 한다.
        self.assertEqual("ai", self._deployment()["metadata"]["name"])
        self.assertEqual(
            "ai", self._deployment()["spec"]["template"]["spec"]["containers"][0]["name"])

    def test_the_manifest_is_actually_in_the_overlay(self):
        """목록에서 빠져 있으면 매니페스트가 클러스터에 들어가지 않는다."""
        kustomization = (K8S / "base" / "kustomization.yaml").read_text(encoding="utf-8")
        self.assertRegex(kustomization, re.compile(r"^\s+- ai\.yaml\s*$", re.M))


class ManifestApplyTest(unittest.TestCase):
    """매니페스트는 저장소에서 고친다고 클러스터에 반영되지 않는다. 하루에 세 번
    같은 사고가 났다 — Secret 은 갱신됐는데 파드가 그 키를 안 읽고, nginx 수정이
    k8s 사본에만 없고, APK 는 올라갔는데 /download/ 가 404 였다.

    이 엔드포인트는 다른 둘보다 힘이 세다. 이미지 수신기는 태그만, 환경변수
    수신기는 Secret 하나만 건드리지만 여기는 클러스터의 모양을 바꾼다."""

    CI = K8S / "ci"
    DEPLOY = K8S / "deploy"

    def test_the_forced_command_takes_nothing_but_a_versioned_bundle(self):
        script = (self.DEPLOY / "ssh-entrypoint-k8s.sh").read_text(encoding="utf-8")
        found = re.search(r"\$command =~ (\^apply-manifests.+?) \]\]", script)
        self.assertTrue(found, "apply-manifests 를 받는 규칙이 없다")
        pattern = re.compile(found.group(1))
        good = "apply-manifests " + "a" * 40 + " " + "b" * 64
        self.assertTrue(pattern.match(good))
        for bad in (good + "; rm -rf /", good + " extra", "apply-manifests",
                    good.replace("a" * 40, "A" * 40),
                    good.replace("b" * 64, "b" * 63)):
            with self.subTest(command=bad):
                self.assertIsNone(pattern.match(bad))

    def test_the_receiver_refuses_a_bundle_that_reaches_outside(self):
        """tar 는 `../` 와 절대경로로 밖에 쓸 수 있다. 이건 root 로 돈다."""
        receiver = (self.DEPLOY / "receive-manifests.py").read_text(encoding="utf-8")
        self.assertIn('name.startswith("/")', receiver)
        self.assertIn('".." in pathlib.PurePosixPath(name).parts', receiver)
        # 경로를 열거로 묶는다. 저 둘 밖은 애초에 받지 않는다.
        self.assertIn('ALLOWED = ("infra_k8s/base/", "infra_k8s/overlays/")', receiver)
        # 심볼릭 링크로도 밖을 가리킬 수 있다.
        self.assertIn("member.isfile()", receiver)

    def test_the_receiver_refuses_manifests_for_another_namespace(self):
        """CI 키를 훔친 쪽이 ClusterRole 을 심으면 클러스터 전체를 쥔다."""
        receiver = (self.DEPLOY / "receive-manifests.py").read_text(encoding="utf-8")
        self.assertIn("assert_only_our_namespace", receiver)
        self.assertIn('CLUSTER_KINDS = {"Namespace"}', receiver)

    def test_the_apply_goes_through_the_guarded_script(self):
        """`kubectl apply -k` 를 직접 쓰면 이미지 태그가 placeholder 로 덮인다.
        maxUnavailable 이 0 이라 옛 Pod 가 계속 서빙해서 겉으로는 멀쩡해 보이고,
        그 Pod 가 죽는 순간 서비스가 내려간다. mupid-apply 가 그 함정 때문에
        존재한다."""
        receiver = (self.DEPLOY / "receive-manifests.py").read_text(encoding="utf-8")
        self.assertIn("/usr/local/sbin/mupid-apply", receiver)
        self.assertNotRegex(receiver, r'"apply",\s*"-k"')

    def test_images_wait_for_the_manifests(self):
        """새 매니페스트가 요구하는 것(볼륨·Secret 키)이 없는 상태로 이미지만
        바뀌면 파드가 못 뜬다."""
        jobs = yaml.safe_load((self.CI / "k8s-release.yml").read_text(encoding="utf-8"))
        for name in ("k8s-deploy-api", "k8s-deploy-web", "k8s-deploy-env"):
            with self.subTest(job=name):
                needs = {n["job"] for n in jobs[name].get("needs", [])}
                self.assertIn("k8s-deploy-manifests", needs)


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

class IndexUploadTest(unittest.TestCase):
    """추천 색인은 지금까지 사람이 노드에 놓았다("placed there by an
    administrator"). 매니페스트를 손으로 적용하던 때와 같은 모양이고, 그때
    "고쳤는데 클러스터에 닿지 않은" 사고가 하루 세 번 났다.

    네 색인은 **같은 행 번호로 서로를 가리킨다.** 한 파일씩 덮으면 그 사이에
    기동한 파드가 서로 다른 판을 섞어 읽고, 그때 서비스는 정상처럼 뜨면서
    엉뚱한 곡을 추천한다."""

    CI = K8S / "ci"
    DEPLOY = K8S / "deploy"

    def test_the_forced_command_takes_nothing_but_a_versioned_bundle(self):
        script = (self.DEPLOY / "ssh-entrypoint-k8s.sh").read_text(encoding="utf-8")
        found = re.search(r"\$command =~ (\^upload-index.+?) \]\]", script)
        self.assertTrue(found, "upload-index 를 받는 규칙이 없다")
        pattern = re.compile(found.group(1))
        good = "upload-index " + "a" * 40 + " " + "b" * 64
        self.assertTrue(pattern.match(good))
        for bad in (good + "; rm -rf /", good + " extra", "upload-index",
                    good.replace("a" * 40, "A" * 40),
                    good.replace("b" * 64, "b" * 63)):
            with self.subTest(command=bad):
                self.assertIsNone(pattern.match(bad))

    def test_the_receiver_refuses_a_bundle_that_reaches_outside(self):
        """tar 는 `../` 와 절대경로로 밖에 쓸 수 있다. 이건 root 로 돈다."""
        receiver = (self.DEPLOY / "receive-index.py").read_text(encoding="utf-8")
        self.assertIn('name.startswith("/")', receiver)
        self.assertIn('".." in pathlib.PurePosixPath(name).parts', receiver)
        self.assertIn("member.isfile()", receiver)

    def test_the_receiver_only_accepts_known_index_files(self):
        """이 엔드포인트로 노드에 임의의 파일을 놓을 수 있으면 안 된다."""
        receiver = (self.DEPLOY / "receive-index.py").read_text(encoding="utf-8")
        self.assertIn("ALLOWED = {", receiver)
        allowed = receiver[receiver.index("ALLOWED = {"):]
        allowed = allowed[:allowed.index("}")]
        # `moments_emb.npy` 도 받는다 — 운영이 로컬 벡터 모드라 판 안의 이 파일로
        # 추천한다(S15P21A204-450). 허용 목록만 잘라서 본다.
        for name in ("moments.parquet", "moments_emb.npy", "features_v1.npz",
                     "stage1_section.npy"):
            with self.subTest(file=name):
                self.assertIn(f'"{name}"', allowed)
        # 경로도 한 겹으로 묶는다. data/proc/mupid 바로 아래만 받는다.
        self.assertIn('INNER = "data/proc/mupid"', receiver)
        self.assertIn("parent != INNER", receiver)

    def test_the_swap_is_atomic(self):
        """한 파일씩 덮으면 그 사이에 기동한 파드가 판을 섞어 읽는다."""
        receiver = (self.DEPLOY / "receive-index.py").read_text(encoding="utf-8")
        self.assertIn("symlink_to(release)", receiver)
        self.assertIn("pending.replace(current)", receiver)

    def test_the_receiver_explains_a_directory_where_the_link_belongs(self):
        """실제로 겪은 고장이다. hostPath 를 `.../current` 로 잡아 두면 kubelet 의
        DirectoryOrCreate 가 그 이름으로 **디렉터리**를 만들고, 그러면 os.replace 가
        IsADirectoryError 로 죽는다. 원문 예외는 원인을 짐작하게 하지 않는다."""
        receiver = (self.DEPLOY / "receive-index.py").read_text(encoding="utf-8")
        self.assertIn("current.exists() and not current.is_symlink()", receiver)

    def test_the_receiver_checks_the_same_thing_the_pod_will(self):
        """여기서 막지 않으면 증상이 "파드는 떴는데 ready 가 영영 false" 로만
        보인다. 그때는 이미 current 가 새 판을 가리켜 되돌릴 대상이 헷갈린다."""
        receiver = (self.DEPLOY / "receive-index.py").read_text(encoding="utf-8")
        self.assertIn("emb_row", receiver)
        self.assertIn("duplicated", receiver)
        self.assertIn("REQUIRED = {", receiver)

    def test_the_mount_matches_where_the_receiver_writes(self):
        """둘이 어긋나면 올리기는 성공하고 파드만 빈 디렉터리를 본다."""
        deployment = [d for d in yaml.safe_load_all(
            (K8S / "base" / "ai.yaml").read_text(encoding="utf-8"))
            if d and d["kind"] == "Deployment"][0]
        spec = deployment["spec"]["template"]["spec"]
        volume = {v["name"]: v for v in spec["volumes"]}["artifacts"]
        # **부모를 마운트한다.** `.../current` 를 직접 마운트하면 kubelet 이 그
        # 이름으로 디렉터리를 만들어 수신기가 링크를 갈아 끼우지 못한다(실제로 겪었다).
        self.assertEqual("/srv/mupid-k8s/ai-artifacts", volume["hostPath"]["path"])
        receiver = (self.DEPLOY / "receive-index.py").read_text(encoding="utf-8")
        self.assertIn('ARTIFACTS = ROOT / "ai-artifacts"', receiver)
        self.assertIn('ROOT = pathlib.Path("/srv/mupid-k8s")', receiver)

        # 마운트 지점과 컨테이너가 읽는 경로도 같아야 한다.
        mount = {m["name"]: m for m in spec["containers"][0]["volumeMounts"]}
        self.assertEqual("/artifacts", mount["artifacts"]["mountPath"])
        self.assertTrue(mount["artifacts"]["readOnly"])
        env = {e["name"]: e.get("value") for e in spec["containers"][0]["env"]}
        # 마운트는 부모이고, 읽는 곳은 그 안의 심볼릭 링크다.
        self.assertEqual("/artifacts/current", env["MUPID_AI_ARTIFACT_DIR"])

    def test_the_volume_is_created_rather_than_required(self):
        """Directory 로 두면 한 번도 올리지 않은 노드에서 **파드가 뜨지도 못해**
        /health 로 원인을 볼 수 없다. 빈 디렉터리로 뜨면 무결성 검사가 실패하고
        그 이유가 /health 에 그대로 실린다."""
        deployment = [d for d in yaml.safe_load_all(
            (K8S / "base" / "ai.yaml").read_text(encoding="utf-8"))
            if d and d["kind"] == "Deployment"][0]
        volume = {v["name"]: v for v in
                  deployment["spec"]["template"]["spec"]["volumes"]}["artifacts"]
        self.assertEqual("DirectoryOrCreate", volume["hostPath"]["type"])

    def test_the_manifest_no_longer_claims_the_pod_is_stateless(self):
        """그 주장은 두 가지 틀린 근거 위에 있었다 — 특징값을 서빙이 읽지
        않는다(읽는다: Catalog.__init__ 이 features.load() 를 부른다)와 임베딩을
        Pinecone 으로 옮긴다(풀이 10,007구간이라 근거가 없다)."""
        text = (K8S / "base" / "ai.yaml").read_text(encoding="utf-8")
        self.assertNotIn("PVC 도 initContainer 도 hostPath 도 없다", text)
        self.assertNotIn("서빙 경로가 읽지 않는다", text)

    def test_the_uploader_refuses_to_send_an_incomplete_index(self):
        """행 정렬된 네 개가 다 있어야 한다. 하나라도 빠지면 수신기가 거절한다.

        `moments_emb.npy` 도 본다 — 로컬 벡터 모드가 운영 경로라 수신기가 요구한다."""
        script = (self.CI / "deploy-index.sh").read_text(encoding="utf-8")
        loop = next(line for line in script.splitlines()
                    if line.startswith("for name in "))
        for name in ("moments.parquet", "moments_emb.npy", "features_v1.npz",
                     "stage1_section.npy"):
            with self.subTest(file=name):
                self.assertIn(name, loop)
        self.assertIn("slice_index", script)

    def test_the_uploader_demands_the_node_the_pod_runs_on(self):
        """색인은 파드가 뜨는 노드의 디스크에 있어야 한다(hostPath). ai 는 node-b 에
        묶여 있는데 배포 엔드포인트는 node-a 다. 처음에 그걸 놓쳐 색인을 node-a 에
        올려 두고, 파드는 node-b 에서 **빈 /artifacts** 를 봤다 — 증상은 /health 의
        data_dir_exists=false 뿐이었다."""
        script = (self.CI / "deploy-index.sh").read_text(encoding="utf-8")
        self.assertIn("MUPID_AI_NODE_SSH", script)
        # 기본값을 두지 않는다. 있으면 잘못된 노드로 조용히 올라간다.
        self.assertNotIn("MUPID_AI_NODE_SSH:-", script)
        self.assertNotIn("mupid-deploy-k8s@j15a204.p.ssafy.io", script)

    def test_the_uploader_never_prints_the_private_key(self):
        """`< "$VAR"` 로 열면 Variable 로 등록돼 있을 때 셸이 열기 실패
        메시지에 개인키 전문을 실어 출력한다 — 실제로 겪었다."""
        script = (self.CI / "deploy-index.sh").read_text(encoding="utf-8")
        self.assertIn("read_secret()", script)
        self.assertNotIn('< "$MUPID_DEPLOY_K8S_SSH_KEY"', script)

    def test_the_bundle_is_reproducible(self):
        """같은 색인이면 같은 체크섬이어야 로그를 비교할 수 있다."""
        script = (self.CI / "deploy-index.sh").read_text(encoding="utf-8")
        for flag in ("--sort=name", "--mtime=@0", "--owner=0", "--group=0"):
            with self.subTest(flag=flag):
                self.assertIn(flag, script)

def _load_receiver():
    """`receive-index.py` 를 모듈로 읽는다. 이름에 하이픈이 있어 import 가 안 된다."""
    import importlib.util

    path = K8S / "deploy" / "receive-index.py"
    spec = importlib.util.spec_from_file_location("mupid_receive_index", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _npy_bytes(rows, cols=4, version=1):
    """헤더만 진짜인 .npy 를 만든다. numpy 없이 만들어야 한다 — 이 시험은
    python:3.12-slim 에서 돌고 거기엔 numpy 가 없다(`infra/ci/manifests.yml`).

    노드도 같은 처지라 파서가 존재하는 것이고, 그래서 시험도 같은 방식으로 만든다.
    """
    header = ("{'descr': '<f4', 'fortran_order': False, "
              "'shape': (%d, %d), }" % (rows, cols))
    if version == 1:
        prefix = b"\x93NUMPY\x01\x00"
        width = 2
    else:
        prefix = b"\x93NUMPY\x02\x00"
        width = 4
    pad = -(len(prefix) + width + len(header) + 1) % 64
    body = (header + " " * pad + "\n").encode("latin-1")
    return (prefix + len(body).to_bytes(width, "little") + body
            + b"\x00" * (rows * cols * 4))


def _npy(path, rows, cols=4, version=1):
    path.write_bytes(_npy_bytes(rows, cols, version))


def _npz(path, rows, cols=4, member="X"):
    """헤더만 진짜인 .npz 를 만든다. npz 는 .npy 를 담은 zip 이라 numpy 없이도
    만들 수 있다. **압축해서** 넣는다 — 수신기는 `np.savez_compressed` 로 나온
    묶음도 읽어야 하고, 안 풀고 생바이트를 훑으면 거기서 깨진다."""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as bundle:
        bundle.writestr(f"{member}.npy", _npy_bytes(rows, cols))


class IndexReceiverCheckTest(unittest.TestCase):
    """노드에는 numpy 도 pandas 도 없다(2026-09-16 실측). 그래서 처음 쓴 전체 검사가
    **운영에서는 건너뛰어졌다** — 막으려고 쓴 검사가 정작 돌지 않았다. NPY 헤더는
    ASCII 라 표준 라이브러리로 읽을 수 있으니 행 수만은 언제나 본다."""

    def setUp(self):
        self.mod = _load_receiver()
        self.tmp = tempfile.mkdtemp()
        self.data = pathlib.Path(self.tmp) / self.mod.INNER
        self.data.mkdir(parents=True)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _fill(self, feat_rows, sec_rows, report_rows=None, emb_rows=None):
        """`emb_rows` 를 안 주면 `moments_emb.npy` 는 구간 벡터와 같은 행 수로 만든다."""
        _npz(self.data / "features_v1.npz", feat_rows)
        _npy(self.data / "stage1_section.npy", sec_rows)
        _npy(self.data / "moments_emb.npy", sec_rows if emb_rows is None else emb_rows)
        (self.data / "moments.parquet").write_bytes(b"PAR1")
        (self.data / "index_meta.json").write_text("{}", encoding="utf-8")
        if report_rows is not None:
            (self.data / "slice_report.json").write_text(
                json.dumps({"rows": {"moments": report_rows}}), encoding="utf-8")

    def test_reads_the_row_count_without_numpy(self):
        _npy(self.data / "a.npy", 10007)
        self.assertEqual(10007, self.mod.npy_rows(self.data / "a.npy"))

    def test_reads_the_v2_header_too(self):
        """v2 는 헤더 길이가 2바이트가 아니라 4바이트다. 폭을 틀리면 엉뚱한 곳을 읽는다."""
        _npy(self.data / "b.npy", 4242, version=2)
        self.assertEqual(4242, self.mod.npy_rows(self.data / "b.npy"))

    def test_refuses_a_file_that_is_not_npy(self):
        (self.data / "c.npy").write_bytes(b"not a numpy file at all")
        with self.assertRaises(SystemExit):
            self.mod.npy_rows(self.data / "c.npy")

    def test_reads_the_row_count_inside_a_compressed_npz(self):
        """npz 안의 배열도 numpy 없이 세어야 한다. `moments_emb.npy` 가 배송에서
        빠진 뒤로 `features_v1.npz` 가 두 번째 대조 갈래라, 여기서 막히면 갈래가
        하나로 줄고 검사가 조용히 무력해진다."""
        _npz(self.data / "d.npz", 4242)
        self.assertEqual(4242, self.mod.npz_rows(self.data / "d.npz"))

    def test_refuses_an_npz_without_the_array(self):
        with zipfile.ZipFile(self.data / "e.npz", "w") as bundle:
            bundle.writestr("Y.npy", b"")
        with self.assertRaises(SystemExit):
            self.mod.npz_rows(self.data / "e.npz")

    def test_mismatched_vectors_are_refused(self):
        """네 색인은 같은 행 번호로 서로를 가리킨다. 서로 다른 판에서 온 파일이
        섞이면 서비스는 정상처럼 뜨면서 엉뚱한 곡을 추천한다 — 오류는 안 난다."""
        self._fill(10007, 9999)
        with self.assertRaises(SystemExit) as caught:
            self.mod.check_rows(self.data)
        self.assertEqual(65, caught.exception.code)

    def test_a_stale_report_is_refused(self):
        """보고서만 새 판이고 파일이 옛 판인 경우. 보고서를 믿으면 놓친다."""
        self._fill(10007, 10007, report_rows=12345)
        with self.assertRaises(SystemExit) as caught:
            self.mod.check_rows(self.data)
        self.assertEqual(65, caught.exception.code)

    def test_a_consistent_bundle_passes_without_numpy(self):
        """numpy 가 없어도 통과해야 한다. 여기서 막히면 배포가 아예 안 된다."""
        self._fill(10007, 10007, report_rows=10007)
        self.assertEqual(10007, self.mod.check_rows(self.data))

    def test_a_missing_index_is_named(self):
        self._fill(10007, 10007)
        (self.data / "features_v1.npz").unlink()
        with self.assertRaises(SystemExit) as caught:
            self.mod.verify(pathlib.Path(self.tmp))
        self.assertEqual(65, caught.exception.code)

    def test_a_bundle_without_embeddings_is_refused(self):
        """로컬 벡터 모드가 운영 경로다. 임베딩이 없는 판으로 current 를 갈아 끼우면
        그 순간 추천이 멈춘다(S15P21A204-450)."""
        self._fill(10007, 10007)
        (self.data / "moments_emb.npy").unlink()
        with self.assertRaises(SystemExit) as caught:
            self.mod.verify(pathlib.Path(self.tmp))
        self.assertEqual(65, caught.exception.code)

    def test_embeddings_from_another_plate_are_refused(self):
        """`Z[emb_row]` 는 행이 하나만 어긋나도 오류 없이 다른 구간의 벡터를 쓴다."""
        self._fill(10007, 10007, report_rows=10007, emb_rows=9334)
        with self.assertRaises(SystemExit) as caught:
            self.mod.check_rows(self.data)
        self.assertEqual(65, caught.exception.code)

    def test_embeddings_must_match_the_moment_count_in_the_report(self):
        """moments.parquet 은 numpy 없이 셀 수 없어 보고서의 moments 와 맞춘다."""
        self._fill(9334, 9334, report_rows=10007, emb_rows=9334)
        with self.assertRaises(SystemExit) as caught:
            self.mod.check_rows(self.data)
        self.assertEqual(65, caught.exception.code)

    def _bundle(self, names):
        import io
        import tarfile

        archive = pathlib.Path(self.tmp) / "index.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            for name in names:
                body = b"x"
                info = tarfile.TarInfo(f"{self.mod.INNER}/{name}")
                info.size = len(body)
                tar.addfile(info, io.BytesIO(body))
        return archive

    def test_the_receiver_extracts_a_bundle_with_embeddings(self):
        """거절하던 동안 node-b 판은 수신기를 거치지 않고 손으로 만들어야 했다."""
        archive = self._bundle(["moments.parquet", "moments_emb.npy"])
        target = pathlib.Path(self.tmp) / "tree"
        target.mkdir()
        self.mod.safe_extract(archive, target)
        self.assertTrue((target / self.mod.INNER / "moments_emb.npy").exists())

    def test_the_receiver_still_refuses_unknown_and_escaping_names(self):
        """받는 이름을 늘렸다고 경계가 느슨해지면 안 된다 — 이건 root 로 돈다."""
        for name in ("moments_emb.npy.sh", "../moments_emb.npy"):
            with self.subTest(name=name):
                archive = self._bundle([name])
                with self.assertRaises(SystemExit) as caught:
                    self.mod.safe_extract(archive, pathlib.Path(self.tmp))
                self.assertEqual(65, caught.exception.code)

class ApplyTagSubstitutionTest(unittest.TestCase):
    """`mupid-apply` 는 placeholder 가 클러스터에 올라가는 것을 막으려고 존재한다.

    그런데 **그 함정을 세 가지 방식으로 밟았다.** 셋 다 배포 job 은 성공으로 끝나고,
    maxUnavailable 이 0 이라 옛 Pod 가 계속 서빙해 겉으로는 멀쩡해 보였다.
    """

    SCRIPT = K8S / "deploy" / "apply.sh"

    def setUp(self):
        self.text = self.SCRIPT.read_text(encoding="utf-8")

    def test_every_component_in_the_overlay_is_substituted(self):
        """ai 가 목록에서 빠져 있었다. ai.yaml 이 오버레이에 들어온 뒤로
        `mupid-ai:placeholder` 가 그대로 클러스터에 올라갔다."""
        self.assertIn('for component in ("api", "web", "ai"):', self.text)

        # 오버레이가 참조하는 base 파일에 placeholder 가 있으면 목록에도 있어야 한다.
        listed = re.search(r'for component in \(([^)]*)\)', self.text).group(1)
        listed = set(re.findall(r'"(\w+)"', listed))
        for path in sorted((K8S / "base").glob("*.yaml")):
            for name in re.findall(r"mupid-(\w+):placeholder", path.read_text(encoding="utf-8")):
                with self.subTest(component=name, file=path.name):
                    self.assertIn(name, listed)

    def test_the_workload_name_is_mapped_not_assumed(self):
        """컴포넌트 이름과 Deployment 이름이 같지 않다 — web 은 클러스터에서 `edge` 다.
        이 한 글자 차이로 클러스터 조회가 조용히 빗나가고 파일로 되돌아간다."""
        self.assertIn('WORKLOAD = {"api": "api", "web": "edge", "ai": "ai"}', self.text)

        # 매니페스트가 실제로 그 이름을 쓰는지 확인한다.
        names = set()
        for path in sorted((K8S / "base").glob("*.yaml")):
            for doc in yaml.safe_load_all(path.read_text(encoding="utf-8")):
                if doc and doc.get("kind") == "Deployment":
                    names.add(doc["metadata"]["name"])
        for expected in ("api", "edge", "ai"):
            with self.subTest(deployment=expected):
                self.assertIn(expected, names)

    def test_the_cluster_lookup_actually_reaches_the_program(self):
        """`cmd | python3 - <<'PY'` 는 **heredoc 이 stdin 을 차지한다.** 파이프로 준
        데이터가 프로그램에 닿지 않고 조용히 빈 값이 된다 — 클러스터를 본다고 적어
        놓고 실제로는 파일만 보고 있었다. 그 파일이 한 판 뒤처져 있어서, 노드에 없는
        이미지를 요구하는 배포가 나갔다."""
        self.assertNotRegex(self.text, r'\|\s*python3 -\s+"')
        self.assertIn('python3 - "$rendered" "$root" "$live"', self.text)
        self.assertIn("live_path.exists()", self.text)

    def test_a_leftover_placeholder_fails_the_job(self):
        """예전에는 경고만 찍고 성공으로 끝났다. 그래서 placeholder 배포가 초록으로
        지나갔다 — 이 검사가 말을 하고도 아무 일이 없었던 셈이다."""
        tail = self.text.split("placeholder 로 남은 워크로드", 1)[1]
        self.assertIn("exit 65", tail)
        # 최초 설치는 막지 않는다. 그때는 치환 단계가 그렇게 말해 둔다.
        self.assertIn("태그를 찾지 못했다", tail)

# `build()` 가 kubectl 을 쓴다. CI 이미지(python:3.12-slim)에는 없어서, 이 표시가
# 없으면 통째로 TypeError 로 무너진다 — 실제로 그렇게 파이프라인을 깼다.
@unittest.skipUnless(KUBECTL, "kubectl not installed")
class LabelBatchTest(unittest.TestCase):
    """라벨이 없으면 **오류 없이 모든 사용자가 콜드스타트**가 된다. 서버는 정상으로
    보이고 개인화만 없다 — 그래서 이 배치는 반드시 주기적으로 돌아야 한다."""

    def _cronjob(self):
        for doc in build("cutover"):
            if doc.get("kind") == "CronJob" and doc["metadata"]["name"] == "ai-labeler":
                return doc
        raise AssertionError("ai-labeler CronJob 을 찾지 못했다")

    def _pod(self):
        return self._cronjob()["spec"]["jobTemplate"]["spec"]["template"]["spec"]


    def test_the_batch_runs_the_same_image_as_serving(self):
        """등급 판정은 한 곳에서만 한다. 배치와 서빙이 다른 코드를 쓰면 라벨의
        의미가 갈린다."""
        self.assertEqual("mupid-ai:placeholder", self._pod()["containers"][0]["image"])

    def test_the_batch_writes_outside_the_index(self):
        """색인 마운트는 읽기 전용이다. 배치가 거기 쓰면 그 순간 실패하고, 열어
        준다 해도 색인을 교체할 때마다 워터마크가 사라져 신호를 처음부터 다시
        당긴다."""
        pod = self._pod()
        mounts = {m["name"]: m for m in pod["containers"][0]["volumeMounts"]}
        self.assertTrue(mounts["artifacts"].get("readOnly"))
        self.assertFalse(mounts["state"].get("readOnly", False))

        volumes = {v["name"]: v["hostPath"]["path"] for v in pod["volumes"]}
        self.assertNotEqual(volumes["artifacts"], volumes["state"])

        env = {e["name"]: e.get("value") for e in pod["containers"][0]["env"]}
        self.assertEqual("/state", env["MUPID_AI_STATE_DIR"])

    def test_serving_reads_the_same_state(self):
        """서빙이 선호 구간·온보딩 씨앗을 그 자리에서 읽는다. 갈리면 배치는 도는데
        화면에는 아무것도 안 나오는, 오류 없는 고장이 된다."""
        deployment = [d for d in build("cutover")
                      if d.get("kind") == "Deployment" and d["metadata"]["name"] == "ai"][0]
        spec = deployment["spec"]["template"]["spec"]
        env = {e["name"]: e.get("value") for e in spec["containers"][0]["env"]}
        self.assertEqual("/state", env["MUPID_AI_STATE_DIR"])

        serving = {v["name"]: v["hostPath"]["path"] for v in spec["volumes"]}
        self.assertEqual(serving["state"],
                         {v["name"]: v["hostPath"]["path"]
                          for v in self._pod()["volumes"]}["state"])

    def test_the_state_dir_is_owned_by_the_container_user(self):
        """hostPath 는 kubelet 이 **root 소유로** 만든다(DirectoryOrCreate). 컨테이너는
        비root(uid 100)로 돌기 때문에 그대로면 첫 쓰기에서 Permission denied 다 —
        실제로 겪었다. 배치가 신호를 다 읽고 워터마크를 저장하다 죽었다.

        fsGroup 은 hostPath 에 적용되지 않는다. 그래서 root 로 한 번 맞춰 준다."""
        init = {c["name"]: c for c in self._pod()["initContainers"]}
        own = init["own-state"]
        self.assertEqual(0, own["securityContext"]["runAsUser"])
        self.assertIn("/state", " ".join(own["command"]))
        # 색인은 건드리지 않는다. 읽기만 하는 것을 root 로 만지면 실수의 여지가 커진다.
        self.assertEqual(["state"], [m["name"] for m in own["volumeMounts"]])

    def test_serving_only_reads_the_state(self):
        """쓰는 것은 `learn` 하나이고 그건 배치에서만 불린다. 읽기 전용으로 두면
        서빙이 배치의 결과를 덮어쓸 길이 없다."""
        deployment = [d for d in build("cutover")
                      if d.get("kind") == "Deployment" and d["metadata"]["name"] == "ai"][0]
        mounts = {m["name"]: m for m
                  in deployment["spec"]["template"]["spec"]["containers"][0]["volumeMounts"]}
        self.assertTrue(mounts["state"].get("readOnly"))

    def test_two_runs_do_not_overlap(self):
        """라벨러는 전체 사용자를 훑어 `section_labels` 를 판째로 갈아 끼운다.
        두 판이 동시에 들어가면 어느 쪽이 최신인지 뒤섞인다."""
        self.assertEqual("Forbid", self._cronjob()["spec"]["concurrencyPolicy"])

    def test_it_does_not_collide_with_the_backup_window(self):
        """MySQL 백업이 한국시간 03:00~03:05 에 돈다(infra/README.md)."""
        spec = self._cronjob()["spec"]
        self.assertEqual("Asia/Seoul", spec["timeZone"])
        minute, hour = spec["schedule"].split()[:2]
        self.assertNotEqual("3", hour, "백업 시간과 겹친다")

    def test_the_pull_runs_before_the_labeler(self):
        """반쯤 당긴 이벤트로 라벨을 만들면 그 판이 최신으로 잡힌다."""
        args = " ".join(self._pod()["containers"][0]["args"])
        self.assertIn("set -e", args)
        self.assertLess(args.index("pull_signals"), args.index("run_labeler"))


class LabelBatchFileTest(unittest.TestCase):
    """kubectl 없이도 도는 부분. CI 이미지에는 kubectl 이 없어서, 여기 있는 것만이
    파이프라인에서 실제로 확인된다."""

    def test_it_is_in_the_overlay(self):
        """목록에서 빠지면 클러스터에 들어가지 않는다 — 그리고 라벨이 안 생기는
        것은 오류가 아니라 침묵이라 한참 뒤에야 드러난다."""
        kustomization = (K8S / "base" / "kustomization.yaml").read_text(encoding="utf-8")
        self.assertRegex(kustomization, re.compile(r"^\s+- ai-labeler\.yaml\s*$", re.M))

    def test_the_batch_and_serving_agree_on_the_state_path(self):
        """갈리면 배치는 도는데 화면에는 아무것도 안 나오는, 오류 없는 고장이 된다."""
        state = []
        for name in ("ai.yaml", "ai-labeler.yaml"):
            text = (K8S / "base" / name).read_text(encoding="utf-8")
            self.assertIn("MUPID_AI_STATE_DIR, value: /state", text)
            found = re.findall(r"path: (/srv/mupid-k8s/ai-state)", text)
            self.assertTrue(found, name)
            state.append(found[0])
        self.assertEqual(state[0], state[1])


class QdrantManifestTest(unittest.TestCase):
    """Qdrant (S15P21A204-456, 설계 §6.1·§6.4). kubectl 없이 파일로 본다 — CI 이미지에 kubectl 이
    없어서 이것만이 파이프라인에서 실제로 확인된다.

    지키는 것: node-b 고정·digest 고정·한도, 밖에 열지 않음, 키가 없으면 잠긴 채 뜸, NetworkPolicy
    가 Qdrant 만 고름(네임스페이스의 첫 정책이라 다른 파드에 번지면 안 된다), ai 는 local 그대로."""

    QDRANT = K8S / "base" / "qdrant.yaml"
    POLICY = K8S / "base" / "qdrant-networkpolicy.yaml"
    DIGEST = re.compile(r"^[\w./-]+@sha256:[0-9a-f]{64}$")

    def _docs(self, path):
        return [d for d in yaml.safe_load_all(path.read_text(encoding="utf-8")) if d]

    def _kind(self, kind):
        return next(d for d in self._docs(self.QDRANT) if d["kind"] == kind)

    def _pod(self):
        return self._kind("StatefulSet")["spec"]["template"]["spec"]

    def _cron_pod(self):
        return self._kind("CronJob")["spec"]["jobTemplate"]["spec"]["template"]["spec"]

    def _base_workloads(self):
        """base 의 모든 파드 템플릿 (이름, 라벨, spec)."""
        for path in sorted((K8S / "base").glob("*.yaml")):
            for d in self._docs(path):
                if d.get("kind") in ("Deployment", "StatefulSet"):
                    t = d["spec"]["template"]
                elif d.get("kind") == "CronJob":
                    t = d["spec"]["jobTemplate"]["spec"]["template"]
                else:
                    continue
                yield d["metadata"]["name"], (t.get("metadata") or {}).get("labels") or {}, t["spec"]

    def test_it_is_in_the_overlay(self):
        kustomization = (K8S / "base" / "kustomization.yaml").read_text(encoding="utf-8")
        for name in ("qdrant.yaml", "qdrant-networkpolicy.yaml"):
            with self.subTest(file=name):
                self.assertRegex(kustomization, re.compile(rf"^\s+- {re.escape(name)}\s*$", re.M))

    def test_it_runs_on_the_ai_node_with_a_node_local_volume(self):
        pod = self._pod()
        self.assertEqual({"mupid.io/role": "ai"}, pod["nodeSelector"])
        claims = self._kind("StatefulSet")["spec"]["volumeClaimTemplates"]
        self.assertEqual({"storage", "snapshots"}, {c["metadata"]["name"] for c in claims})
        for c in claims:
            self.assertEqual("local-path", c["spec"]["storageClassName"])
        # 스냅샷은 노드 볼륨에만 — Object Storage 자격(S3 키)을 받지 않는다 (§6.1 백업).
        text = self.QDRANT.read_text(encoding="utf-8")
        self.assertNotIn("MUPID_S3_", text)
        self.assertNotIn("mupid-storage-s3", text)

    def test_images_are_pinned_by_digest(self):
        for where, pod in (("qdrant", self._pod()), ("snapshot", self._cron_pod())):
            for c in pod["containers"]:
                with self.subTest(pod=where, container=c["name"]):
                    self.assertRegex(c["image"], self.DIGEST)

    def test_requests_are_small_and_limits_are_set(self):
        """node-b 는 할당 가능 3코어에 요청이 이미 1,275m 다. 요청이 크면 다른 파드가 Pending 이다."""
        for where, pod in (("qdrant", self._pod()), ("snapshot", self._cron_pod())):
            for c in pod["containers"]:
                res = c["resources"]
                with self.subTest(pod=where):
                    self.assertTrue({"cpu", "memory"} <= set(res["requests"]))
                    self.assertTrue({"cpu", "memory"} <= set(res["limits"]))
                    cpu = res["requests"]["cpu"]
                    milli = int(cpu[:-1]) if cpu.endswith("m") else int(float(cpu) * 1000)
                    self.assertLessEqual(milli, 250)

    def test_it_is_never_exposed_outside_the_cluster(self):
        svc = self._kind("Service")
        self.assertEqual("ClusterIP", svc["spec"].get("type", "ClusterIP"))
        for p in svc["spec"]["ports"]:
            self.assertNotIn("nodePort", p)
        for c in self._pod()["containers"]:
            for p in c.get("ports", []):
                self.assertNotIn("hostPort", p)
        self.assertFalse(self._pod().get("hostNetwork"))
        conf = (K8S / "base" / "nginx" / "default.conf").read_text(encoding="utf-8")
        self.assertNotIn("qdrant", conf.lower())

    def test_it_never_starts_open_when_a_key_is_missing(self):
        """Qdrant 는 api_key 가 비면 인증 없이 열린다. 비면 무작위 키로 잠가야 한다."""
        c = self._pod()["containers"][0]
        env = {e["name"]: e for e in c["env"]}
        refs = {
            "QDRANT__SERVICE__API_KEY": ("mupid-secrets", "MUPID_QDRANT_API_KEY"),
            "QDRANT__SERVICE__READ_ONLY_API_KEY": ("mupid-ai-secrets", "MUPID_QDRANT_READ_ONLY_API_KEY"),
        }
        script = c["args"][0]
        for name, (secret, key) in refs.items():
            with self.subTest(env=name):
                ref = env[name]["valueFrom"]["secretKeyRef"]
                self.assertEqual((secret, key), (ref["name"], ref["key"]))
                self.assertTrue(ref.get("optional"), "비밀 전에 배포돼도 파드는 떠야 한다")
                self.assertIn(name, script)
        self.assertIn('if [ -z "${!name:-}" ]', script)
        self.assertIn("/dev/urandom", script)
        self.assertIn("exec ./entrypoint.sh", script)
        self.assertLess(script.index("/dev/urandom"), script.index("exec ./entrypoint.sh"))
        for pod in (self._pod(), self._cron_pod()):
            # kubelet 이 args 의 `$(` 를 먼저 펼친다 (clickhouse.yaml 과 같은 이유).
            self.assertNotIn("$(", " ".join(pod["containers"][0]["args"]))

    def test_the_snapshot_job_targets_the_defined_collection(self):
        schema = json.loads((ROOT / "ai_server" / "app" / "qdrant_collection.json").read_text(encoding="utf-8"))
        c = self._cron_pod()["containers"][0]
        env = {e["name"]: e for e in c["env"]}
        self.assertEqual(schema["collection"], env["COLLECTION"]["value"])
        self.assertGreaterEqual(int(env["KEEP"]["value"]), 1)
        cron = self._kind("CronJob")["spec"]
        self.assertEqual("Asia/Seoul", cron["timeZone"])
        self.assertEqual("Forbid", cron["concurrencyPolicy"])
        # 라벨러(04:00)·MySQL 백업(03:00)과 같은 시각이 아니다.
        labeler = next(d for d in self._docs(K8S / "base" / "ai-labeler.yaml") if d["kind"] == "CronJob")
        self.assertNotEqual(labeler["spec"]["schedule"].split()[1], cron["schedule"].split()[1])
        self.assertNotEqual("3", cron["schedule"].split()[1])

    def test_the_policy_selects_only_qdrant(self):
        """네임스페이스의 첫 NetworkPolicy 다. 다른 파드를 고르면 그 파드의 연결이 전부 막힌다."""
        policies = [d for p in (K8S / "base").glob("*.yaml") for d in self._docs(p)
                    if d.get("kind") == "NetworkPolicy"]
        self.assertEqual(["qdrant-ingress"], [p["metadata"]["name"] for p in policies])
        spec = policies[0]["spec"]
        self.assertEqual({"matchLabels": {"app": "qdrant"}}, spec["podSelector"])
        self.assertEqual(["Ingress"], spec["policyTypes"], "나가는 연결은 건드리지 않는다")
        selected = [name for name, labels, _ in self._base_workloads() if labels.get("app") == "qdrant"]
        self.assertEqual(["qdrant"], selected)

    def test_the_policy_admits_exactly_the_design_table(self):
        """설계 §6.4: 쓰기 Spring, 읽기 ai·라벨러 (+ 스냅샷 작업). 라벨이 실제 파드에 있어야 한다."""
        spec = self._docs(self.POLICY)[0]["spec"]
        (rule,) = spec["ingress"]
        (peer,) = rule["from"]
        (expr,) = peer["podSelector"]["matchExpressions"]
        allowed = set(expr["values"])
        self.assertEqual({"api", "ai", "ai-labeler", "qdrant-snapshot"}, allowed)
        self.assertNotIn("namespaceSelector", peer)
        self.assertNotIn("ipBlock", peer)
        present = {labels.get("app") for _, labels, _ in self._base_workloads()}
        self.assertTrue(allowed <= present, f"라벨이 없는 파드: {sorted(allowed - present)}")
        self.assertEqual({6333, 6334}, {p["port"] for p in rule["ports"]})

    def test_k3s_keeps_its_network_policy_controller(self):
        """k3s 는 kube-router 로 NetworkPolicy 를 지킨다. 이 플래그가 생기면 정책이 조용히 무력해진다."""
        for role in ("k3s_server", "k3s_agent"):
            text = (K8S / "ansible" / "roles" / role / "tasks" / "main.yml").read_text(encoding="utf-8")
            with self.subTest(role=role):
                self.assertNotIn("disable-network-policy", text)
                self.assertNotIn("flannel-backend=none", text)

    def test_ai_stays_on_the_local_backend(self):
        """병합이 곧 배포다. 매니페스트가 갈래를 바꾸면 벡터가 채워지기 전에 추천이 멈춘다."""
        text = (K8S / "base" / "ai.yaml").read_text(encoding="utf-8")
        self.assertNotRegex(text, re.compile(r"name:\s*MUPID_VECTOR_BACKEND\s*,\s*value:\s*'?(qdrant|pinecone)"))

    def test_the_keys_are_written_down(self):
        doc = (ROOT / "infra" / "ci" / "VARIABLES.md").read_text(encoding="utf-8")
        for key in ("MUPID_QDRANT_READ_ONLY_API_KEY", "MUPID_QDRANT_API_KEY", "MUPID_VECTOR_BACKEND"):
            with self.subTest(key=key):
                self.assertIn(key, doc)
        runbook = (K8S / "OPERATIONS-K8S.md").read_text(encoding="utf-8")
        self.assertIn("rollout restart statefulset/qdrant", runbook)
        self.assertIn("qdrant_collection", runbook)


if __name__ == "__main__":
    unittest.main()
