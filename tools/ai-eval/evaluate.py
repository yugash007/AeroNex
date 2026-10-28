"""Score aeronex-ai-eval results against corpus.json (spec 2026-10-15 "Command path", safety rules A2-A4).

Two pipelines are scored from the same model outputs:
- shipped: the app's AI path. A rule-level refusal is final (A2, emulated with the lexicon negators, minus words and
  other units), the
  planner splits the utterance, each step is read by the rules when it is a canonical command (out of range: final)
  and by the Needle call otherwise, every call is grounded against the utterance and its gloss (A4: numbers after
  number-word normalisation, directions with their synonyms), and the FlightPlan structure rules apply.
  The planner's own command check (LfmPlanner.commandsMatch: a take off / land / hold / status step must be said, and
  one the operator said must be planned) and the Needle mapping (needle_result.h) are part of the shipped code.
- shipped_strict: shipped plus the count check this evaluation recommends for A4: a number grounds at most as many
  calls as the operator said it (no duplicated steps).
- ai_only: no rules gate and Needle for every step: what the two models do by themselves (grounding and the
  structure rules still apply).

    python tools/ai-eval/evaluate.py --corpus tools/ai-eval/corpus.json --results <results.jsonl> --out <report.json>
        [--markdown <per-utterance table.md>]
"""
import argparse
import json
import math
from pathlib import Path
import re

import yaml

ROOT = Path(__file__).resolve().parents[2]
LEXICON_DIR = ROOT / 'grammar' / 'lexicon'

ONES = {'zero': 0, 'oh': 0, 'one': 1, 'two': 2, 'three': 3, 'four': 4, 'five': 5, 'six': 6, 'seven': 7, 'eight': 8,
        'nine': 9, 'ten': 10, 'eleven': 11, 'twelve': 12, 'thirteen': 13, 'fourteen': 14, 'fifteen': 15,
        'sixteen': 16, 'seventeen': 17, 'eighteen': 18, 'nineteen': 19}
TENS = {'twenty': 20, 'thirty': 30, 'forty': 40, 'fifty': 50, 'sixty': 60, 'seventy': 70, 'eighty': 80, 'ninety': 90}
# Direction words a call may be grounded on (spec A4; N1's synonyms included).
DIRECTION_WORDS = {
    'forward': {'forward', 'forwards', 'ahead', 'advance', 'front'},
    'backward': {'backward', 'backwards', 'back', 'reverse', 'retreat'},
    'left': {'left'},
    'right': {'right'},
    'up': {'up', 'upward', 'upwards', 'climb', 'ascend', 'rise', 'higher', 'raise'},
    'down': {'down', 'downward', 'downwards', 'descend', 'drop', 'lower'},
}
SIGN_WORDS = {'minus', 'negative'}
# LfmPlanner.COMMAND_WORDS: per command, the phrases that name it and those of them that ask for it.
COMMAND_WORDS = [
    (['take off', 'taking off', 'takeoff', 'launch', 'lift off', 'liftoff'],) * 2,
    (['land', 'landing', 'touch down', 'touchdown'],) * 2,
    (['hold', 'holding', 'hover', 'hovering', 'wait', 'stay', 'pause'], ['hold', 'holding', 'hover', 'hovering']),
    (['status', 'telemetry', 'battery', 'health', 'report'],) * 2,
]
NEGATIVE_CATEGORIES = ('negation', 'out-of-range', 'unsafe', 'missing-number', 'indic-negation', 'indic-range')
NUMBER = r'[0-9]+(?:\.[0-9]+)?'
# A single step the 0.4.0 rules read without AI, approximated for planner-shaped steps: the v0.3.0 vocabulary of
# grammar/lexicon/en.yaml (move verbs, direction words, metre words, "to the left", the takeoff/land/hover/status
# commands) plus the N1 additions the plan lists (verb synonyms, "by N", number words). Not the real StepParser:
# re-run this evaluation on the final code (N6).
RULE_DIRECTIONS = {'forward': 'forward', 'forwards': 'forward', 'ahead': 'forward', 'backward': 'backward',
                   'backwards': 'backward', 'back': 'backward', 'left': 'left', 'right': 'right', 'up': 'up',
                   'upward': 'up', 'upwards': 'up', 'down': 'down', 'downward': 'down', 'downwards': 'down'}
# N1 verb synonyms that carry their direction ("climb 2 meters").
RULE_DIRECTED_VERBS = {'climb': 'up', 'ascend': 'up', 'rise': 'up', 'go higher': 'up', 'descend': 'down',
                       'drop': 'down', 'lower': 'down', 'advance': 'forward', 'reverse': 'backward',
                       'retreat': 'backward', 'back up': 'backward'}
_DIR = '(?P<dir>' + '|'.join(RULE_DIRECTIONS) + ')'
_UNIT = '(?:meters|meter|metres|metre|m)'
_VERB = '(?:(?:move|go|fly|come) )?'
_TOWARD = '(?:(?:to|toward|towards) )?(?:(?:the|your) )?'
_BY = '(?:by )?'
_AMOUNT = '(?P<dist>[a-z0-9. ]+?)'
CANONICAL_MOVE = [re.compile(rf'^{_VERB}{_TOWARD}{_DIR} {_BY}{_AMOUNT} {_UNIT}$'),
                  re.compile(rf'^{_VERB}{_BY}{_AMOUNT} {_UNIT} {_TOWARD}{_DIR}$')]
CANONICAL_DIRECTED = re.compile(rf'^(?P<verb>{"|".join(RULE_DIRECTED_VERBS)}) {_BY}{_AMOUNT} {_UNIT}$')
CANONICAL_TAKEOFF = re.compile(rf'^(?:take off|takeoff|launch)(?: to)? (?P<alt>[a-z0-9. ]+?) {_UNIT}$')
CANONICAL_SIMPLE = {'hold': 'hold', 'hover': 'hold', 'hold position': 'hold', 'land': 'land', 'status': 'status',
                    'telemetry': 'status'}
TOOL_NAMES = {'takeoff': 'takeoff', 'move_relative': 'move', 'hold': 'hold', 'land': 'land', 'get_status': 'status'}


def words(text):
    """Lowercase words; digits keep their sign and decimal point, other punctuation splits."""
    text = text.lower().replace('’', "'")
    return re.findall(r"-?[0-9]+(?:\.[0-9]+)?|[^\W\d_]+(?:'[^\W\d_]+)?", text)


def number_list(text):
    """Every number the text says, in order: digits or English number words ("two point five", "one and a half",
    "half"); "minus"/"negative" before a number makes it negative."""
    tokens = words(text)
    found = []
    i = 0
    sign = 1
    while i < len(tokens):
        token = tokens[i]
        if token in SIGN_WORDS:
            sign = -1
            i += 1
            continue
        if re.fullmatch('-?' + NUMBER, token):
            found.append(sign * float(token))
            sign = 1
            i += 1
            continue
        if token == 'half':
            found.append(sign * 0.5)
            sign = 1
            i += 1
            continue
        if token not in ONES and token not in TENS:
            sign = 1
            i += 1
            continue
        value = 0
        if token in TENS:
            value = TENS[token]
            i += 1
            if i < len(tokens) and tokens[i] in ONES and ONES[tokens[i]] < 10:
                value += ONES[tokens[i]]
                i += 1
        else:
            value = ONES[token]
            i += 1
        if i < len(tokens) and tokens[i] == 'hundred':
            value *= 100
            i += 1
        if i + 1 < len(tokens) and tokens[i] == 'point' and tokens[i + 1] in ONES and ONES[tokens[i + 1]] < 10:
            digits = ''
            i += 1
            while i < len(tokens) and tokens[i] in ONES and ONES[tokens[i]] < 10:
                digits += str(ONES[tokens[i]])
                i += 1
            value = float(f'{value}.{digits}')
        elif tokens[i:i + 3] == ['and', 'a', 'half']:
            value += 0.5
            i += 3
        found.append(sign * float(value))
        sign = 1
    return found


def numbers_in(text):
    return set(number_list(text))


def call_text(call):
    name, direction, value = call
    if name == 'move':
        return f'move {direction} {value:g}'
    if name == 'takeoff':
        return f'takeoff {value:g}'
    return name


def parse_expected(text):
    parts = text.split()
    if parts[0] == 'move':
        return ('move', parts[1], float(parts[2]))
    if parts[0] == 'takeoff':
        return ('takeoff', None, float(parts[1]))
    return (parts[0], None, None)


def in_range(call):
    name, _, value = call
    if name == 'takeoff':
        return 1 <= value <= 10
    if name == 'move':
        return 0 < value <= 10
    return True


def amount(text):
    """One number, in digits or number words ("2.5", "two and a half"), or None."""
    values = number_list(text)
    if len(values) != 1:
        return None
    spelled = words(text)
    allowed = set(ONES) | set(TENS) | {'point', 'and', 'a', 'half', 'hundred'}
    if not all(re.fullmatch(NUMBER, word) or word in allowed for word in spelled):
        return None
    return values[0]


def canonical(step):
    """The rules' reading of a planner step (the session parses every step with the rules first), or None."""
    if step in CANONICAL_SIMPLE:
        return (CANONICAL_SIMPLE[step], None, None)
    match = CANONICAL_TAKEOFF.match(step)
    if match:
        value = amount(match.group('alt'))
        return ('takeoff', None, value) if value is not None else None
    match = CANONICAL_DIRECTED.match(step)
    if match:
        value = amount(match.group('dist'))
        return ('move', RULE_DIRECTED_VERBS[match.group('verb')], value) if value is not None else None
    for pattern in CANONICAL_MOVE:
        match = pattern.match(step)
        if match:
            value = amount(match.group('dist'))
            return ('move', RULE_DIRECTIONS[match.group('dir')], value) if value is not None else None
    return None


def needle_call(tool):
    """The tool call of one Needle step (after needle_result.h), or None; mirrors NeedleToolCaller's checks."""
    call = (tool.get('result') or {}).get('call')
    if not call:
        return None
    name = TOOL_NAMES.get(call.get('name'))
    arguments = call.get('arguments') or {}
    if name == 'takeoff' and set(arguments) == {'altitude_m'} and isinstance(arguments['altitude_m'], (int, float)):
        parsed = ('takeoff', None, float(arguments['altitude_m']))
    elif name == 'move' and set(arguments) == {'direction', 'distance_m'} and \
            arguments['direction'] in DIRECTION_WORDS and isinstance(arguments['distance_m'], (int, float)):
        parsed = ('move', arguments['direction'], float(arguments['distance_m']))
    elif name in ('hold', 'land', 'status') and not arguments:
        parsed = (name, None, None)
    else:
        return None
    return parsed if in_range(parsed) else None  # FlightCommand.fromToolCalls limits


def _listed(value):
    if isinstance(value, dict):
        value = value.get('words', [])
    return [str(word) for word in value or []]


def load_negators():
    """Per language, the rule tables' final-refusal words (spec A2) from the grammar lexicons:
    {'negators': set, 'suffixes': list, 'minus': set, 'units': set}."""
    result = {}
    for path in sorted(LEXICON_DIR.glob('*.yaml')):
        data = yaml.safe_load(path.read_text(encoding='utf-8'))
        rules = data.get('rules', {})
        negators = set(_listed(rules.get('negators')))
        negators.update(_listed(data.get('grammar_negators')))
        result[data['language']] = {'negators': negators, 'suffixes': _listed(rules.get('negator_suffixes')),
                                    'minus': set(_listed(rules.get('minus_words'))),
                                    'units': set(_listed(rules.get('other_units')))}
    return result


def rules_refusal(item, tables):
    """A2 (emulated): the rules refuse a negated utterance, a negative number or another unit before any AI, and
    that refusal is final. The native tables apply to the utterance, the English ones also to its gloss."""
    language = item['language']
    for text, table in ((item['utterance'], tables.get(language)),
                        (item.get('plannerInput', item['utterance']), tables['en'])):
        if table is None:
            continue
        lowered = text.lower().replace('’', "'")
        tokens = re.sub(r'[,.!?;:]', ' ', lowered).split()
        if any(token in table['negators'] for token in tokens) or \
                any(' ' in phrase and re.search(rf'\b{re.escape(phrase)}\b', lowered)
                    for phrase in table['negators']) or \
                any(token.endswith(suffix) for token in tokens for suffix in table['suffixes'] if suffix):
            return 'negation'
        if any(token in table['minus'] for token in tokens) or re.search(r'(^|\s)-[0-9]', lowered):
            return 'negative number'
        if any(token in table['units'] for token in tokens):
            return 'other unit'
    return None


def negated(item, tables):
    return rules_refusal(item, tables) == 'negation'


def planner_words(text):
    """LfmPlanner.words: lowercase letter/digit runs, space-joined with a space at both ends."""
    return ' ' + ' '.join(re.findall(r'[^\W_]+', text.lower())) + ' '


def planner_commands_problem(text, steps):
    """LfmPlanner.commandsMatch: a step naming take off / land / hold / status must match the operator's words, and one
    the operator asked for must be in a step ("wait" and "hold on" do not ask for a hold)."""
    said = planner_words(text)
    asked = said.replace(' hold on ', ' ')
    planned = [planner_words(step) for step in steps]
    for phrases, triggers in COMMAND_WORDS:
        in_plan = any(f' {phrase} ' in step for step in planned for phrase in phrases)
        if in_plan and not any(f' {phrase} ' in said for phrase in phrases):
            return f'"{phrases[0]}" not said'
        if not in_plan and any(f' {phrase} ' in asked for phrase in triggers):
            return f'"{phrases[0]}" said but not planned'
    return None


def grounding_problem(call, item):
    """A4: a call's number (sign included) and direction must appear in the utterance or its gloss (None when
    grounded)."""
    texts = [item['utterance'], item.get('plannerInput', '')]
    name, direction, value = call
    if value is not None:
        said = set().union(*(numbers_in(text) for text in texts))
        if not any(math.isclose(value, number, abs_tol=1e-9) for number in said):
            return f'number {value:g} not said'
    said_words = set().union(*(set(words(text)) for text in texts))
    if direction is not None and not DIRECTION_WORDS[direction] & said_words:
        return f'direction {direction} not said'
    return None


def count_problem(calls, item):
    """Recommended: a number grounds at most as many calls as the operator said it (no duplicated steps)."""
    texts = [item['utterance'], item.get('plannerInput', '')]
    for value in {call[2] for call in calls if call[2] is not None}:
        used = sum(1 for call in calls if call[2] is not None and math.isclose(call[2], value, abs_tol=1e-9))
        said = max(sum(1 for number in number_list(text) if math.isclose(number, value, abs_tol=1e-9))
                   for text in texts)
        if used > said:
            return f'number {value:g} used {used} times, said {said}'
    return None


def plan_problem(calls):
    """FlightPlan structure rules (1-5 steps, status alone, takeoff first, land last)."""
    names = [call[0] for call in calls]
    if not calls:
        return 'empty plan'
    if len(calls) > 5:
        return 'more than 5 steps'
    if len(names) > 1 and 'status' in names:
        return 'status not on its own'
    if 'takeoff' in names[1:]:
        return 'takeoff not first'
    if 'land' in names[:-1]:
        return 'land not last'
    return None


def run_pipeline(item, line, tables, shipped, strict=False):
    """{'accepted': [calls] | None, 'rejected': reason | None, 'grounding_reject': bool, 'via': [...]}."""
    outcome = {'accepted': None, 'rejected': None, 'grounding_reject': False, 'via': []}
    refusal = rules_refusal(item, tables) if shipped else None
    if refusal:
        outcome['rejected'] = f'{refusal} (rules, final)'
        return outcome
    if 'error' in line:
        outcome['rejected'] = 'planner error: ' + line['error']
        return outcome
    plan = line['plan']
    if plan['stop'] != 'done':
        outcome['rejected'] = 'planner ' + plan['stop']
        return outcome
    if not plan['steps']:
        outcome['rejected'] = 'planner refused'
        return outcome
    if shipped:
        problem = planner_commands_problem(item.get('plannerInput', item['utterance']), plan['steps'])
        if problem:
            outcome['rejected'] = 'planner grounding: ' + problem
            outcome['grounding_reject'] = True
            return outcome
    calls = []
    for step, tool in zip(plan['steps'], line['tools']):
        rule = canonical(step) if shipped else None
        if rule is not None:
            if not in_range(rule):
                outcome['rejected'] = f'rules: "{step}" out of range (final)'
                return outcome
            calls.append(rule)
            outcome['via'].append('rules')
            continue
        call = needle_call(tool)
        if call is None:
            reason = tool.get('error') or (tool.get('result') or {}).get('reason') or 'invalid call'
            outcome['rejected'] = f'step "{step}" not understood ({reason})'
            return outcome
        calls.append(call)
        outcome['via'].append('needle')
    problems = [grounding_problem(call, item) for call in calls]
    if strict:
        problems.append(count_problem(calls, item))
    problem = next((problem for problem in problems if problem), None)
    if problem:
        outcome['rejected'] = 'grounding: ' + problem
        outcome['grounding_reject'] = True
        return outcome
    problem = plan_problem(calls)
    if problem:
        outcome['rejected'] = 'plan rules: ' + problem
        return outcome
    outcome['accepted'] = calls
    return outcome


def same_plan(calls, expected):
    return len(calls) == len(expected) and all(
        a[0] == b[0] and a[1] == b[1] and (a[2] is None) == (b[2] is None) and
        (a[2] is None or math.isclose(a[2], b[2], abs_tol=1e-9)) for a, b in zip(calls, expected))


def verdict(outcome, expected):
    if outcome['accepted'] is None:
        return 'correct-reject' if not expected else 'missed'
    if not expected:
        return 'FALSE-ACCEPT'
    return 'exact' if same_plan(outcome['accepted'], expected) else 'WRONG-ACCEPT'


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(fraction * len(ordered)) - 1))
    return round(ordered[index], 1)


def summary(rows, key):
    total = len(rows)
    counts = {}
    for row in rows:
        counts[row[key]['verdict']] = counts.get(row[key]['verdict'], 0) + 1
    good = counts.get('exact', 0) + counts.get('correct-reject', 0)
    positives = [row for row in rows if row['expected']]
    negatives = [row for row in rows if not row['expected']]
    return {
        'utterances': total,
        'exact_match': good,
        'exact_match_rate': round(good / total, 3) if total else None,
        'positive_exact': sum(row[key]['verdict'] == 'exact' for row in positives),
        'positive_total': len(positives),
        'negative_correct_rejects': sum(row[key]['verdict'] == 'correct-reject' for row in negatives),
        'negative_total': len(negatives),
        'false_accepts': counts.get('FALSE-ACCEPT', 0),
        'wrong_accepts': counts.get('WRONG-ACCEPT', 0),
        'missed': counts.get('missed', 0),
        'grounding_rejects': sum(row[key]['grounding_reject'] for row in rows),
        'false_accepts_by_category': {category: sum(row[key]['verdict'] == 'FALSE-ACCEPT' for row in rows
                                                    if row['category'] == category)
                                      for category in NEGATIVE_CATEGORIES},
    }


def evaluate(corpus, lines):
    negators = load_negators()
    load = next((line for line in lines if line.get('kind') == 'load'), {})
    results = {line['id']: line for line in lines if line.get('kind') == 'utterance'}
    rows = []
    for item in corpus['utterances']:
        line = results[item['id']]
        expected = [parse_expected(text) for text in item['expected']]
        row = {'id': item['id'], 'language': item['language'], 'category': item['category'],
               'utterance': item['utterance'], 'plannerInput': item.get('plannerInput', item['utterance']),
               'expected': item['expected'], 'steps': line.get('plan', {}).get('steps'),
               'plannerText': line.get('plan', {}).get('text'), 'plannerStop': line.get('plan', {}).get('stop'),
               'plannerMs': round(line['plan']['promptMs'] + line['plan']['generateMs'], 1) if 'plan' in line else None,
               'needleMs': [round(tool['ms'], 1) for tool in line.get('tools', [])],
               'needleCalls': [call_text(c) if (c := needle_call(tool)) else
                               (tool.get('error') or (tool.get('result') or {}).get('reason'))
                               for tool in line.get('tools', [])],
               'totalMs': round(line['totalMs'], 1)}
        for key, shipped, strict in (('shipped', True, False), ('shipped_strict', True, True),
                                     ('ai_only', False, False)):
            outcome = run_pipeline(item, line, negators, shipped, strict)
            row[key] = {'accepted': [call_text(call) for call in outcome['accepted']] if outcome['accepted'] else None,
                        'rejected': outcome['rejected'], 'grounding_reject': outcome['grounding_reject'],
                        'via': outcome['via'], 'verdict': verdict(outcome, expected)}
        planner_calls = [canonical(step) for step in row['steps'] or []]
        row['planner_exact'] = (not expected and not row['steps']) or (
            bool(row['steps']) and None not in planner_calls and same_plan(planner_calls, expected))
        rows.append(row)
    planner_ms = [row['plannerMs'] for row in rows if row['plannerMs'] is not None]
    needle_ms = [ms for row in rows for ms in row['needleMs']]
    total_ms = [row['totalMs'] for row in rows]
    groups = {'all': rows, 'english': [row for row in rows if row['language'] == 'en'],
              'indic_glossed': [row for row in rows if row['language'] != 'en']}
    return {
        'schema': 'aeronex-ai-eval/1',
        'load': load,
        'shipped': {name: summary(group, 'shipped') for name, group in groups.items()},
        'shipped_strict': {name: summary(group, 'shipped_strict') for name, group in groups.items()},
        'ai_only': {name: summary(group, 'ai_only') for name, group in groups.items()},
        'planner_exact': {name: sum(row['planner_exact'] for row in group) for name, group in groups.items()},
        'latency_ms': {
            'planner': {'p50': percentile(planner_ms, 0.5), 'p95': percentile(planner_ms, 0.95),
                        'max': round(max(planner_ms), 1) if planner_ms else None},
            'needle_step': {'p50': percentile(needle_ms, 0.5), 'p95': percentile(needle_ms, 0.95),
                            'max': round(max(needle_ms), 1) if needle_ms else None, 'calls': len(needle_ms)},
            'utterance_total': {'p50': percentile(total_ms, 0.5), 'p95': percentile(total_ms, 0.95),
                                'max': round(max(total_ms), 1)},
        },
        'utterances': rows,
    }


def markdown(report):
    lines = ['| id | input to planner | planner steps | Needle per step | shipped result | verdict | ms |',
             '|---|---|---|---|---|---|---|']
    for row in report['utterances']:
        steps = '; '.join(row['steps']) if row['steps'] else f"({row['plannerStop'] or 'error'}: none)"
        needle = '; '.join(str(call) for call in row['needleCalls']) or '-'
        shipped = row['shipped']
        result = ', '.join(shipped['accepted']) if shipped['accepted'] else 'rejected: ' + shipped['rejected']
        lines.append(f"| {row['id']} | {row['plannerInput']} | {steps} | {needle} | {result} | "
                     f"{shipped['verdict']} | {row['totalMs']:.0f} |".replace('\n', ' '))
    return '\n'.join(lines) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--corpus', type=Path, required=True)
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--markdown', type=Path)
    args = parser.parse_args()
    corpus = json.loads(args.corpus.read_text(encoding='utf-8'))
    lines = [json.loads(line) for line in args.results.read_text(encoding='utf-8').splitlines() if line.strip()]
    report = evaluate(corpus, lines)
    args.out.write_text(json.dumps(report, indent=1, ensure_ascii=False) + '\n', encoding='utf-8')
    if args.markdown:
        args.markdown.write_text(markdown(report), encoding='utf-8')
    print(json.dumps({key: report[key] for key in ('shipped', 'shipped_strict', 'ai_only', 'planner_exact',
                                                  'latency_ms')}, indent=1))


if __name__ == '__main__':
    main()
