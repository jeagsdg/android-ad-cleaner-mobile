"""Compute session-level metrics from independently labeled, completed runs (CSV).

Required fields: case_id, environment, ad, safe_exit, detected, attempted, verified_closed, wrong_action.
A blank value is unknown. Results from different environments are never pooled.
"""
import argparse
import csv
import json
from collections import defaultdict


def ratio(rows, numerator, denominator):
    population = [r for r in rows if denominator(r) is True]
    resolved = [numerator(r) for r in population]
    unknown_denominator = sum(denominator(r) is None for r in rows)
    hits = sum(x is True for x in resolved)
    # Suppress a point estimate if labels are incomplete.
    value = hits / len(population) if population and None not in resolved and not unknown_denominator else None
    return dict(hits=hits, denominator=len(population), unknown_outcome=resolved.count(None),
                unknown_denominator=unknown_denominator, ratio=value)


def evaluate(records):
    groups = defaultdict(list)
    seen = set()
    flags = ('ad','safe_exit','detected','attempted','verified_closed','wrong_action')
    for record in records:
        key = record['environment'],record['case_id']
        if not all(key) or key in seen:
            raise ValueError('Each labeled case must have a unique id in its environment')
        seen.add(key)
        row = {k:{'1':True,'0':False,'':None}[record[k].strip()] for k in flags}
        if row['verified_closed'] and (row['attempted'] is not True or row['ad'] is not True):
            raise ValueError('Verified close requires an observed action and a labeled advertisement')
        groups[key[0]].append(row)
    output={}
    for environment, rows in groups.items():
        def normal(row):
            return None if row['ad'] is None else not row['ad']
        output[environment]={
            'cases':len(rows),
            'actionable_success':ratio(rows,lambda r:r['verified_closed'],lambda r:r['safe_exit']),
            'recognition_recall':ratio(rows,lambda r:r['detected'],lambda r:r['ad']),
            'processing_coverage':ratio(rows,lambda r:r['verified_closed'],lambda r:r['ad']),
            'post_action_success':ratio(rows,lambda r:r['verified_closed'],lambda r:r['attempted']),
            'normal_page_wrong_action':ratio(rows,lambda r:r['wrong_action'],normal)}
    return output


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv')
    args=parser.parse_args()
    with open(args.csv,encoding='utf-8-sig',newline='') as source:
        print(json.dumps(evaluate(csv.DictReader(source)),ensure_ascii=False,indent=2))
