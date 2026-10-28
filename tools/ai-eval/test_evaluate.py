"""Tests for the AI eval scorer and the corpus (python -m pytest tools/ai-eval -q)."""
import json
from pathlib import Path

import pytest

import evaluate

CORPUS = json.loads((Path(__file__).parent / 'corpus.json').read_text(encoding='utf-8'))


@pytest.mark.parametrize('text, numbers', [
    ('move three meters forward', {3.0}),
    ('Move Three Meters forward', {3.0}),
    ('two point five meters', {2.5}),
    ('go up one and a half meters', {1.5}),
    ('move left half a meter', {0.5}),
    ('twenty three meters', {23.0}),
    ('one hundred meters', {100.0}),
    ('zero point five', {0.5}),
    ('move 2.5 m then 3', {2.5, 3.0}),
    ('take off to three meters then move forward twelve meters', {3.0, 12.0}),
    ('hover in place', set()),
])
def test_numbers_in_reads_digits_and_number_words(text, numbers):
    assert evaluate.numbers_in(text) == numbers


def test_canonical_steps_are_what_the_rules_read():
    assert evaluate.canonical('take off to 3 meters') == ('takeoff', None, 3.0)
    assert evaluate.canonical('move forward 2.5 meters') == ('move', 'forward', 2.5)
    assert evaluate.canonical('move up 1 meter') == ('move', 'up', 1.0)
    assert evaluate.canonical('hold') == ('hold', None, None)
    assert evaluate.canonical('hover') == ('hold', None, None)
    assert evaluate.canonical('move back 2 meters') == ('move', 'backward', 2.0)
    assert evaluate.canonical('go 3 meters ahead') == ('move', 'forward', 3.0)
    assert evaluate.canonical('telemetry') == ('status', None, None)
    assert evaluate.canonical('fly forward a little bit') is None
    assert evaluate.canonical('fly to the left 2 meters') == ('move', 'left', 2.0)
    # The N1 additions: number words and verb synonyms.
    assert evaluate.canonical('move forward three meters') == ('move', 'forward', 3.0)
    assert evaluate.canonical('move forward two and a half meters') == ('move', 'forward', 2.5)
    assert evaluate.canonical('climb 3 meters') == ('move', 'up', 3.0)
    assert evaluate.canonical('take off to five meters') == ('takeoff', None, 5.0)
    assert evaluate.canonical('disarm') is None
    assert evaluate.canonical('move forward 2 and 3 meters') is None


def test_grounding_needs_the_number_and_the_direction_in_the_operators_words():
    item = {'utterance': 'climb two meters'}
    assert evaluate.grounding_problem(('move', 'up', 2.0), item) is None
    assert evaluate.grounding_problem(('move', 'up', 3.0), item) == 'number 3 not said'
    assert evaluate.grounding_problem(('move', 'forward', 2.0), item) == 'direction forward not said'
    gloss = {'utterance': 'डेढ़ मीटर पीछे जाओ', 'plannerInput': '1.5 meter backward go'}
    assert evaluate.grounding_problem(('move', 'backward', 1.5), gloss) is None
    assert evaluate.grounding_problem(('land', None, None), {'utterance': 'do a flip'}) is None


def test_negation_gate_uses_the_lexicon_negators():
    negators = evaluate.load_negators()
    assert evaluate.negated({'language': 'en', 'utterance': "don't move forward three meters"}, negators)
    assert evaluate.negated({'language': 'en', 'utterance': 'you shouldn’t climb'}, negators)
    assert evaluate.negated({'language': 'kn', 'utterance': 'ಮೇಲೆ ಹೋಗಬೇಡ', 'plannerInput': 'up ಹೋಗಬೇಡ'}, negators)
    assert evaluate.negated({'language': 'hi', 'utterance': 'आगे मत जाओ', 'plannerInput': 'forward not go'}, negators)
    assert not evaluate.negated({'language': 'en', 'utterance': 'go forward now, then another two meters'}, negators)


def line(steps, tools, stop='done'):
    return {'kind': 'utterance', 'plan': {'steps': steps, 'stop': stop, 'promptMs': 1.0, 'generateMs': 2.0},
            'tools': tools, 'totalMs': 3.0}


def needle(name, **arguments):
    return {'result': {'call': {'name': name, 'arguments': arguments}, 'reason': ''}, 'ms': 1.0}


NEGATORS = evaluate.load_negators()


def test_the_shipped_path_reads_canonical_steps_by_rules_and_others_by_needle():
    item = {'language': 'en', 'utterance': 'take off to three meters then head to the front two meters'}
    result = line(['take off to 3 meters', 'head to the front 2 meters'],
                  [needle('takeoff', altitude_m=3.0), needle('move_relative', direction='forward', distance_m=2.0)])
    outcome = evaluate.run_pipeline(item, result, NEGATORS, shipped=True)
    assert outcome['accepted'] == [('takeoff', None, 3.0), ('move', 'forward', 2.0)]
    assert outcome['via'] == ['rules', 'needle']


def test_an_out_of_range_canonical_step_is_final_even_if_needle_clamped_it():
    item = {'language': 'en', 'utterance': 'take off to 100 meters'}
    result = line(['take off to 100 meters'], [needle('takeoff', altitude_m=10.0)])
    assert 'out of range' in evaluate.run_pipeline(item, result, NEGATORS, shipped=True)['rejected']
    ai_only = evaluate.run_pipeline(item, result, NEGATORS, shipped=False)
    assert ai_only['grounding_reject'] and ai_only['accepted'] is None


def test_an_invented_number_is_a_grounding_reject():
    item = {'language': 'en', 'utterance': 'do not take off'}
    result = line(['move forward 2 meters'], [needle('move_relative', direction='forward', distance_m=2.0)])
    assert evaluate.run_pipeline(item, result, NEGATORS, shipped=True)['rejected'] == 'negation (rules, final)'
    ai_only = evaluate.run_pipeline(item, result, NEGATORS, shipped=False)
    assert ai_only['rejected'] == 'grounding: number 2 not said'


def test_plan_structure_rules_apply():
    item = {'language': 'en', 'utterance': 'land then take off to two meters'}
    result = line(['land', 'take off to 2 meters'], [needle('land'), needle('takeoff', altitude_m=2.0)])
    assert evaluate.run_pipeline(item, result, NEGATORS, shipped=True)['rejected'] == 'plan rules: takeoff not first'


def test_verdicts():
    assert evaluate.verdict({'accepted': None}, []) == 'correct-reject'
    assert evaluate.verdict({'accepted': [('land', None, None)]}, []) == 'FALSE-ACCEPT'
    assert evaluate.verdict({'accepted': [('land', None, None)]}, [('land', None, None)]) == 'exact'
    assert evaluate.verdict({'accepted': [('move', 'up', 2.0)]}, [('move', 'up', 3.0)]) == 'WRONG-ACCEPT'
    assert evaluate.verdict({'accepted': None}, [('land', None, None)]) == 'missed'


def test_the_corpus_has_the_required_coverage():
    utterances = CORPUS['utterances']
    english = [item for item in utterances if item['language'] == 'en']
    indic = [item for item in utterances if item['language'] != 'en']
    assert len(english) >= 60 and len(indic) >= 30
    assert {item['language'] for item in indic} == {'hi', 'te', 'ta', 'kn', 'ml'}
    assert all('plannerInput' in item for item in indic)
    assert len({item['id'] for item in utterances}) == len(utterances)
    categories = {item['category'] for item in english}
    assert {'multi-step', 'word-order', 'number-words', 'fillers', 'negation', 'out-of-range', 'unsafe'} <= categories
    for item in utterances:
        expected = [evaluate.parse_expected(text) for text in item['expected']]
        assert all(evaluate.in_range(call) for call in expected), item['id']
        if item['category'] in evaluate.NEGATIVE_CATEGORIES:
            assert expected == [], item['id']
        else:
            assert expected and evaluate.plan_problem(expected) is None, item['id']
            assert all(evaluate.grounding_problem(call, item) is None for call in expected), item['id']


def test_numbers_keep_their_sign():
    assert evaluate.numbers_in('move back minus two meters') == {-2.0}
    assert evaluate.numbers_in('move back -2 meters') == {-2.0}
    item = {'language': 'en', 'utterance': 'move back minus two meters'}
    assert evaluate.grounding_problem(('move', 'backward', 2.0), item) == 'number 2 not said'


def test_negative_numbers_and_other_units_are_final_rule_refusals():
    assert evaluate.rules_refusal({'language': 'en', 'utterance': 'move back minus two meters'}, NEGATORS) == \
        'negative number'
    assert evaluate.rules_refusal({'language': 'en', 'utterance': 'go up five feet'}, NEGATORS) == 'other unit'
    assert evaluate.rules_refusal({'language': 'en', 'utterance': 'go up five meters'}, NEGATORS) is None


def test_strict_grounding_refuses_duplicated_numbers():
    twice = {'utterance': '2 meter up fly then hover', 'plannerInput': '2 meter up fly then hover'}
    assert evaluate.count_problem([('move', 'up', 2.0), ('move', 'up', 2.0), ('hold', None, None)], twice) == \
        'number 2 used 2 times, said 1'
    square = {'utterance': 'forward five meters, right two meters, back five meters, left two meters'}
    calls = [('move', 'forward', 5.0), ('move', 'right', 2.0), ('move', 'backward', 5.0), ('move', 'left', 2.0)]
    assert evaluate.count_problem(calls, square) is None


def test_the_planner_command_check_mirrors_lfm_planner():
    problem = evaluate.planner_commands_problem
    assert problem('turn off the motors', ['hold']) == '"hold" not said'
    assert problem("what's the drone status", ['land']) == '"land" not said'
    assert problem('before you land, go back two meters', ['move back 2 meters']) == '"land" said but not planned'
    assert problem('after taking off to six meters move left four meters', ['move left 4 meters']) == \
        '"take off" said but not planned'
    assert problem('hover in place', ['hold']) is None
    assert problem('Take off to five meters then touch down', ['take off to 5 meters', 'land']) is None
    assert problem('wait, go left two meters', ['move left 2 meters']) is None
    assert problem('hold on, go right one meter', ['move right 1 meter']) is None
