#!/usr/bin/env python3
"""Render every Argo CD application of deploy/kubernetes/gitops the way Argo CD would.

The app of apps is rendered with its default values (plus --set overrides); then each Application
it produces is rendered from its source: an upstream chart with value files from this repository
($values/...) and inline valuesObject, or a chart in this repository. The manifests land in
OUT_DIR/<application>.yaml for kubeconform. Also extracted, for the tools that check them:
OUT_DIR/rules/*.yaml (alert rules, and the dashboard's queries as recording rules, for promtool)
and OUT_DIR/alloy/config.alloy (for `alloy validate`).

Usage: render_gitops.py OUT_DIR [--set key=value ...]      (needs helm on PATH and PyYAML)
"""
import argparse
import json
import pathlib
import subprocess
import sys

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
GITOPS = REPO / "deploy" / "kubernetes" / "gitops"
KUBERNETES_VERSION = "1.36.6"
# CRDs the cluster has before Argo CD syncs (installed by Ansible), so charts render their objects.
API_VERSIONS = ["monitoring.coreos.com/v1", "cert-manager.io/v1", "cilium.io/v2"]


def helm(*args):
    result = subprocess.run(["helm", *map(str, args)], capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"helm {' '.join(map(str, args))} failed:\n{result.stderr}")
    return result.stdout


def render_application(app, out):
    name = app["metadata"]["name"]
    namespace = app["spec"]["destination"]["namespace"]
    sources = app["spec"].get("sources") or [app["spec"]["source"]]
    for source in sources:
        if "ref" in source and "chart" not in source and "path" not in source:
            continue  # only provides $values
        settings = source.get("helm", {})
        args = ["template", settings.get("releaseName", name)]
        if "chart" in source:
            args += [source["chart"], "--repo", source["repoURL"], "--version", source["targetRevision"]]
        else:
            args += [REPO / source["path"]]
        args += ["--namespace", namespace, "--kube-version", KUBERNETES_VERSION]
        for api in API_VERSIONS:
            args += ["--api-versions", api]
        for value_file in settings.get("valueFiles", []):
            args += ["-f", REPO / value_file.removeprefix("$values/")]
        if settings.get("valuesObject"):
            inline = out / "values" / f"{name}.yaml"
            inline.write_text(yaml.safe_dump(settings["valuesObject"]), encoding="utf-8")
            args += ["-f", inline]
        manifest = helm(*args)
        (out / f"{name}.yaml").write_text(manifest, encoding="utf-8")
        kinds = {}
        for item in yaml.safe_load_all(manifest):
            if item:
                kinds[item["kind"]] = kinds.get(item["kind"], 0) + 1
        wave = app["metadata"].get("annotations", {}).get("argocd.argoproj.io/sync-wave", "-")
        print(f"  {name:24} wave {wave:>2}  {namespace:12} {sum(kinds.values()):4} objects")


def extract_checked_configs(out):
    """Alert rules and dashboard queries for promtool; the Alloy pipeline for alloy validate."""
    rules_dir, alloy_dir = out / "rules", out / "alloy"
    rules_dir.mkdir(exist_ok=True)
    alloy_dir.mkdir(exist_ok=True)
    gps = REPO / "deploy" / "kubernetes" / "gps" / "files"
    groups = yaml.safe_load((gps / "alerts.yaml").read_text(encoding="utf-8"))
    (rules_dir / "gps-alerts.yaml").write_text(yaml.safe_dump({"groups": groups}, sort_keys=False), encoding="utf-8")
    dashboard = json.loads((gps / "dashboard.json").read_text(encoding="utf-8"))
    queries = [{"record": f"dashboard:panel{i}_{target['refId'].lower()}", "expr": target["expr"]}
               for i, panel in enumerate(dashboard["panels"]) for target in panel["targets"]]
    (rules_dir / "gps-dashboard-queries.yaml").write_text(
        yaml.safe_dump({"groups": [{"name": "dashboard-queries", "rules": queries}]}, sort_keys=False),
        encoding="utf-8")
    alloy_values = yaml.safe_load((GITOPS / "values" / "alloy.yaml").read_text(encoding="utf-8"))
    (alloy_dir / "config.alloy").write_text(alloy_values["alloy"]["configMap"]["content"], encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("out_dir")
    parser.add_argument("--set", action="append", default=[], dest="overrides")
    options = parser.parse_args()
    out = pathlib.Path(options.out_dir)
    (out / "values").mkdir(parents=True, exist_ok=True)

    args = ["template", "gitops", GITOPS]
    for override in options.overrides:
        args += ["--set", override]
    gitops = helm(*args)
    (out / "gitops.yaml").write_text(gitops, encoding="utf-8")
    print("Applications:")
    for document in yaml.safe_load_all(gitops):
        if document and document.get("kind") == "Application":
            render_application(document, out)
    extract_checked_configs(out)


if __name__ == "__main__":
    main()
