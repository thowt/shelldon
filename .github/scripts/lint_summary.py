#!/usr/bin/env python3
# Shelldon SSH
# Copyright 2026 the Shelldon SSH contributors
# SPDX-License-Identifier: Apache-2.0
"""Print the findings of Android lint's XML reports so a CI log is readable without the report.

Usage: lint_summary.py <reports directory>

Prints every error and fatal finding with its location, then a count of warnings per issue id.
Also appends a short table to $GITHUB_STEP_SUMMARY when that variable is set. Always exits 0: the
lint task itself decides whether the job fails.
"""

import collections
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    reports = sorted(glob.glob(os.path.join(root, "lint-results-*.xml")))
    if not reports:
        print("No lint-results-*.xml found under", root)
        return 0

    summary_lines = []
    for report in reports:
        issues = ET.parse(report).getroot().findall("issue")
        errors = [i for i in issues if i.get("severity") in ("Error", "Fatal")]
        others = [i for i in issues if i.get("severity") not in ("Error", "Fatal")]
        print(f"== {os.path.basename(report)}: {len(errors)} errors, {len(others)} other findings")
        for issue in errors:
            location = issue.find("location")
            where = ""
            if location is not None:
                where = f"{location.get('file', '?')}:{location.get('line', '?')}"
            print(f"ERROR [{issue.get('id')}] {where}\n    {issue.get('message')}")
        counts = collections.Counter(i.get("id") for i in others)
        for issue_id, count in counts.most_common(25):
            print(f"  warning {issue_id}: {count}")
        summary_lines.append(f"| {os.path.basename(report)} | {len(errors)} | {len(others)} |")

    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if target:
        with open(target, "a", encoding="utf-8") as out:
            out.write("### Android lint\n\n| Report | Errors | Other findings |\n| --- | --- | --- |\n")
            out.write("\n".join(summary_lines) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
