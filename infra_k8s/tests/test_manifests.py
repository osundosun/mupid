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
OVERLAYS = ("parallel", "cutover")
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


class SecretKeyTest(unittest.TestCase):
    """Every referenced Secret key must be one the administrator knows to set."""

    def test_referenced_keys_exist_in_the_env_example(self):
        example = (ROOT / "infra" / "deploy" / ".env.example").read_text(encoding="utf-8")
        declared = {
            line.split("=", 1)[0].strip()
            for line in example.splitlines()
            if "=" in line and not line.lstrip().startswith("#")
        }
        referenced = set()
        for path in (K8S / "base").glob("*.yaml"):
            for doc in yaml.safe_load_all(path.read_text(encoding="utf-8")):
                for ref in self._secret_refs(doc):
                    referenced.add(ref)
        missing = sorted(referenced - declared)
        self.assertEqual(
            [], missing,
            f"manifests reference Secret keys absent from .env.example: {missing}",
        )

    @staticmethod
    def _secret_refs(node):
        if isinstance(node, dict):
            ref = node.get("secretKeyRef")
            if isinstance(ref, dict) and "key" in ref:
                yield ref["key"]
            for value in node.values():
                yield from SecretKeyTest._secret_refs(value)
        elif isinstance(node, list):
            for value in node:
                yield from SecretKeyTest._secret_refs(value)


@unittest.skipUnless(KUBECTL, "kubectl not installed")
class BuildTest(unittest.TestCase):
    def test_both_overlays_build(self):
        for overlay in OVERLAYS:
            with self.subTest(overlay=overlay):
                self.assertTrue(build(overlay))

    def test_every_workload_is_pinned_to_a_node_role(self):
        """No registry means an image exists on exactly one node. An unpinned pod
        would schedule onto the other one and fail with ErrImageNeverPull."""
        for overlay in OVERLAYS:
            docs = build(overlay)
            for doc in docs:
                if doc.get("kind") not in ("Deployment", "StatefulSet"):
                    continue
                selector = doc["spec"]["template"]["spec"].get("nodeSelector") or {}
                with self.subTest(overlay=overlay, name=doc["metadata"]["name"]):
                    self.assertIn("mupid.io/role", selector)

    def test_parallel_overlay_stays_off_the_production_ports(self):
        edge = by_kind(build("parallel"), "Deployment", "edge")
        ports = {p["name"]: p.get("hostPort") for p in containers(edge)[0]["ports"]}
        self.assertEqual({"http": 8080, "https": 8443}, ports)

    def test_cutover_overlay_takes_the_production_ports(self):
        edge = by_kind(build("cutover"), "Deployment", "edge")
        ports = {p["name"]: p.get("hostPort") for p in containers(edge)[0]["ports"]}
        self.assertEqual({"http": 80, "https": 443}, ports)

    def test_no_pod_mounts_the_compose_data_directories(self):
        """The Compose stack keeps serving production during the parallel run.
        Sharing its volumes would corrupt them."""
        forbidden = ("/var/lib/docker", "/srv/mupid/config")
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
        cgroup quota. Left alone the pod oversubscribes and gets slower."""
        ai = containers(by_kind(build("parallel"), "Deployment", "ai"))[0]
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
        for overlay in OVERLAYS:
            totals = {}
            for doc in build(overlay):
                if doc.get("kind") not in ("Deployment", "StatefulSet"):
                    continue
                spec = doc["spec"]["template"]["spec"]
                role = spec["nodeSelector"]["mupid.io/role"]
                for container in spec["containers"]:
                    cpu = container["resources"]["requests"]["cpu"]
                    milli = int(cpu[:-1]) if cpu.endswith("m") else int(float(cpu) * 1000)
                    totals[role] = totals.get(role, 0) + milli
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

    def test_inventory_uses_private_addresses_for_node_to_node(self):
        """k3s must advertise the private address. Pointing k3s_node_ip at the
        public one exposes the apiserver to the internet."""
        text = (self.ANSIBLE / "inventory.ini").read_text(encoding="utf-8")
        found = re.findall(r"k3s_node_ip=(\S+)", text)
        self.assertEqual(2, len(found))
        for address in found:
            with self.subTest(address=address):
                self.assertTrue(address.startswith("172.26."))

    def test_firewall_opens_forwarding_on_the_cni_interfaces(self):
        """The hosts default to `deny (routed)`. Without a route rule k3s comes up
        Ready while every pod-to-pod packet is dropped — the least obvious way
        this cluster can fail."""
        tasks = self._load(self.ANSIBLE / "roles" / "firewall" / "tasks" / "main.yml")[0]
        routed = [t for t in tasks if (t.get("community.general.ufw") or {}).get("route")]
        self.assertTrue(routed, "no `ufw route` rule; pod networking will fail silently")

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
                wanted = doc["spec"]["template"]["spec"]["nodeSelector"]["mupid.io/role"]
                with self.subTest(workload=doc["metadata"]["name"]):
                    self.assertIn(wanted, roles)


if __name__ == "__main__":
    unittest.main()
