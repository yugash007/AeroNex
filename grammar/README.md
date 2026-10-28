# Canonical grammar v1

This folder is the single source of every voice-command vocabulary in the app
(architecture v2 spec 4.8 and 7.2). It holds two kinds of file:

- `intents.v1.yaml` is the intent schema.
- `lexicon/<lang>.yaml` holds one language's surface forms.

`tools/grammar-gen/gen.py` reads both and writes every recognizer artifact and
the Kotlin parser tables. **Never edit a generated file.** Edit the YAML and
run the generator. The recognizers and the parser then cannot drift apart.

## Running the generator

From the repository root (Python 3 and PyYAML 6.0.2, see
`tools/grammar-gen/requirements.txt`):

```
python tools/grammar-gen/gen.py                 # regenerate
python tools/grammar-gen/gen.py --check         # drift gate: exit 1 if any output differs
python -m pytest tools/grammar-gen -q           # generator tests
python tools/grammar-gen/gen.py --suggest-fillers te --count 300
```

The output is deterministic: sorted JSON keys, LF line ends, no timestamps.
`--check` fails if a generated file is missing, differs, or is stale (for
example a `vosk_xx.json` with no lexicon behind it). A plain run deletes stale
files.

**The Vosk vocabulary gate.** Vosk silently drops grammar words that are
missing from the model, so the generator checks every word of every Vosk phrase
list against the model's vocabulary (`graph/Gr.fst`). The models are looked up
in `../tooling/vosk/<model>` by default. Other options:

- `--vosk-models DIR` uses another models folder.
- `--vosk-words LANG=FILE` uses a word list, a `Gr.fst` or a model folder for
  one language.
- `--require-vosk` fails when a model is not found. Without it, a missing model
  is skipped with a note.
- `--no-vosk` skips the check.

A missing word fails the run and nothing is written. The message names the
word, its table, the model and the fix, for example:

```
vosk en (vosk-model-small-en-in-0.4): command word "forward" (class {dir}) is not in the model vocabulary ...
```

The fix is a corrected spelling, or the word under `vosk.exclude` when another
form of its slot covers it. The generator checks that one does: every
direction, every template class and every number 0–100 must keep at least one
form the model can emit, or the run fails ("the Vosk grammar has no form for
direction 'forward'"). Otherwise a spoken "forward" would be forced onto
another word.

Last full check (2026-10-14): en-in 0.4, hi 0.22 and te 0.42 all passed.

`python -I tools/grammar-gen/vosk_eval.py negation|stop|plans` repeats the host
A/B measurements quoted below. It decodes eSpeak NG clips with the desktop Vosk
package (see `tools/grammar-gen/requirements.txt`). TTS clips on a desktop are
not real speakers on a phone, so read its counts as A/B comparisons only.

## Outputs

| File | What it is |
|---|---|
| `app/android/app/src/main/kotlin/com/aeronex/app/grammar/GrammarTables.kt` | The Kotlin tables (see "Kotlin"). |
| `app/android/app/src/main/assets/grammar/vosk_<lang>.json` | The Vosk command grammar for en, hi and te. |
| `app/android/app/src/main/assets/grammar/stop_<lang>.json` | The Vosk stop-only grammar. |
| `app/android/app/src/main/assets/grammar/ctc_lexicon_<lang>.json` | The CTC template-matching lexicon for te, ta, kn and ml. |
| `app/android/app/src/main/assets/grammar/grammar_manifest.json` | One index of all of the above. |
| `app/android/app/src/test/resources/grammar/fixtures_<lang>.json` | CI fixtures for the parser and the recognizers. |

**`vosk_<lang>.json`** is a plain JSON array of phrases, sorted, to pass
as-is to `new Recognizer(model, 16000f, grammarJson)`. A Vosk grammar compiles
into a bigram LM, so the list holds pairs, not whole sentences. It contains:

- every adjacent word pair of every template, with the classes expanded;
- the negation pairs (see "Negation in the grammar");
- the connective pairs that join two commands (see "Plans");
- every recognizable lexicon word as a single-word phrase, so that numbers,
  negations, units and refused words (centimeters, minus, hundred) are decoded
  and then refused, and never forced onto a command word;
- the fillers (see "Fillers");
- `[unk]`.

**`stop_<lang>.json`** is the stop-only grammar for the second recognizer:
- the stop-lane phrases;
- every lexicon word and filler as a single-word decoy, so a spoken "hover" or
  "land" is not forced onto "stop". Near-stop words are left out (see "Stop
  lane and keyword spotting");
- `[unk]`.

**`ctc_lexicon_<lang>.json`** has these keys:
- `templates`: `[{intent, tokens}]`. A token is a word in native script, a
  slot (`{num}`, `{dir}`) or a fixed class (`{verb}`, `{unit}`, `{takeoff}`
  ...). Multi-word class forms are spelled out as their own templates.
- `fixed`: the single-word forms of each fixed class.
- `slots.num` (phrase to value) and `slots.dir` (form to canonical direction).
- `reject_numbers`: numerals with a scale word. Since 0.4.0 the halves are
  `slots.num` values (see "Spoken numbers"), so this is empty for te, ta, kn
  and ml.
- `negation.words` and `negation.suffixes`: any of them rejects.
- `stop_words`, `connectives` and `provisional_words`.

The matching rules are in spec 4.8: slots match exactly, and fixed words are
matched by edit distance.

**`grammar_manifest.json`** has these keys:
- `languages.<lang>`:
  - `name`, `provisional` and `rule_vocabulary`;
  - `stop_lane` and `kws_keywords`;
  - `vosk`: the model, the file names, the sha256 values, the phrase and
    filler counts, or `null`;
  - `ctc`: the lexicon and its sha256, or `null`.
- `kws.keywords` and `kws.lines`: the ready keyword-file lines per spotter
  model.
- `source_sha256`: the hash of the YAML sources. `GrammarTables.SOURCE_SHA256`
  holds the same value.

**`fixtures_<lang>.json`** has these groups:
- `positive`: text, intent, tool and arguments.
- `loans`: the same, said with loanwords.
- `negated`: must be refused.
- `plans`: a move and a land joined by each connective, with both steps.
- `unk`: the `[unk]` policy cases of spec 4.9.
- `numbers`: phrase, value and kind.
- `out_of_grammar`: filler sentences, Vosk languages only.

## Schema

### intents.v1.yaml

- `languages`: the six lexicons, in join order. `rule_languages`: see "Parser
  parity".
- `intents`: status, takeoff(h), move(dir, d), hold, stop, land. `confirm`,
  `cancel` and `rtl` are listed with `enabled: false`: CONFIRM and Cancel are
  on-screen buttons in Phase 1b, and RTL waits for a safety review. A template
  cannot use a disabled intent.
- `numerals` (0–100) and `halves` (0.5, 1.5, 2.5): spec 7.4, checked for every
  language. Since 0.4.0 the halves are read as their value.
- `spoken_numbers.below` (10): the bound of the composed decimals (see
  "Spoken numbers").
- `fillers`: 200–2,000 (spec 4.6).
- `engines.vosk` (language to model) and `engines.ctc`.
- `kws`: see "Stop lane and keyword spotting".

### lexicon/&lt;lang&gt;.yaml

| Section | Contents |
|---|---|
| `language`, `name`, `script` | Code, display name (the Dart `languageNames`; its lower case is the spoken alias), and the script block plus digit zero. |
| `rules` | The v0.2.0 rule-engine tables (`rule_intent_engine.dart`): move verbs, directions, units, commands (STOP, TAKEOFF, LAND, HOVER phrases), negators, negator suffixes, minus words, other units, scale words, number tables. |
| `plan` | The `plan_interpreter.dart` tables: connectives, "first" words, courtesy, motion verbs, fractions, measure, sequence, filler, correction, altitude, takeoff, simple and land words, context fillers, the Hindi light verb, the wake phrase. |
| `transcript` | The `command_transcript.dart` tables: negators, fused negators, English negation words and apostrophes, number words and preceders, spellings, hold aliases, bare takeoff, known mishearings, foreign scripts. |
| `grammar_negators` | Negations only the recognizers need (en "won't", "do not"; te negative imperatives such as వెళ్ళకు). |
| `stop_lane` | The voice STOP lane triggers. |
| `numerals` | Additions to the rule number tables: `extra`, `fractions` (the halves), `decimal_point` and `and_half` (0.4.0, see "Spoken numbers") and `loans` (English numbers in this script). |
| `natural` | 0.4.0 `direction_verbs` and `comparatives` by canonical direction (see "Natural phrasing"). |
| `loanwords` | English command words in this script: `{loan, latin, class, native}`. |
| `templates` | Per-intent word orders: words, `{class}` references and `[optional]` tokens. |
| `vosk` | `exclude`, `loan_numerals`, `negators`, `near_stop`, `filler_exclude` and `fillers` (see below). |

Rules the loader enforces:
- Every string is Unicode NFC, with no stray spaces.
- There are no duplicates in a table and no unknown keys.
- A template literal must be a word of the lexicon.
- A loanword must not repeat a rule form, and its `native` must be a form of
  its class.
- Every value 0–100 and every half has a numeral.
- Every `stop_lane` phrase is a STOP phrase.
- No form belongs to two directions.
- Every `vosk.negators` entry is a negation of the lexicon.

When the Vosk grammars are built, the generator also checks these:
- Every direction, template class and number 0–100 keeps a form the model can
  emit.
- No filler is a lexicon word or a near-stop word.
- Fillers number 200–2,000.

YAML is read with YAML 1.2 booleans: `no`, `yes`, `on` and `off` stay strings.
Quote them anyway.

**Entry flags.** These are the forms a table can take:
- an item `{w: word, provisional: true}`;
- a table `{provisional: true, words: [...]}`;
- a section that sets `provisional: true` as the default for its tables;
- `{same_as: rules.move_verbs}`, which copies another table.

**Provisional** means "awaiting native-speaker review" (spec 7.2, 7.5). It is
set on:
- every entry the Dart source marks `PROVISIONAL`;
- every v2 addition: zero, the halves, loan numerals, loanwords, the hi/te stop
  lane and negations;
- the whole of ta, kn and ml.

The flags reach `Lexicon.provisionalWords` in Kotlin and `provisional_words` in
the CTC lexicons.

## Parser parity

The `rules`, `plan` and `transcript` sections of en, hi and te
(`rule_languages`) are the v0.2.0 Dart tables, verbatim and in source order.
The Kotlin parser (T10) reads them from `GrammarTables.kt` and must reproduce
all 3,431 vectors in `app/test/golden/plan_vectors.json`. Change these sections
only together with that contract.

`tools/grammar-gen/test_gen.py` compares every table with the Dart source for
as long as the Dart files exist. It also compares the ta, kn and ml `rules`
with the provisional `edge/app/intent.py` reference. The v0.2.0 Dart parser had
no ta, kn or ml vocabulary. Their tables feed the CTC lexicons and are marked
`ruleVocabulary = false`. The Dart connective and transcript-negator entries
for ta, kn and ml are in their `plan` and `transcript` sections.

**Not in the grammar.** Some v0.2.0 logic is code, not vocabulary, so T10 ports
it from the Dart source:
- the character classes and regex shapes: edge punctuation, dashes, the clause
  separator, unit symbols, `take[-\s]*off`, the `take off up to` and verb
  before takeoff rewrites, and the Devanagari/Telugu rule-script check;
- the digit normalisation. Its digit zeros are in `script.digit_zero`;
- the error messages.

## Numerals

`Lexicon.numerals` lists every spoken number of a language, each with a value
and a kind:

- **RULE**: the v0.2.0 rule tables (`ones`, `tens` and `numbers`, plus each
  tens word followed by a 1–9 word, as in "twenty three" or "ఇరవై మూడు").
- **EXTRA**: native forms added in v2, such as zero (సున్నా, शून्य) and hi
  पचानवे (95, the model's spelling).
- **LOAN**: English numbers in native script (త్రీ, थ्री), including the
  tens + ones combinations.
- **FRACTION** (0.4.0): a spoken decimal or half, read as its value. These
  are the halves (half, half a, అర, आधा, डेढ़, ढाई, ఒకటిన్నర, ஒன்றரை …, the
  loans हाफ, హాఫ్ …) and the en decimals composed from `decimal_point` and
  `and_half` (see "Spoken numbers"). Before 0.4.0 the halves were REJECT.
- **REJECT**: recognised, then refused ("say a plain number"). These are the
  RULE or EXTRA phrases that contain a v0.2.0 scale or fraction word (en
  "hundred" and "one hundred").

Every language covers 0–100 without LOAN forms, plus 0.5, 1.5 and 2.5. Numbers
are slots: they match exactly, and are never fuzzy or snapped (spec 4.8).
"fifteen" decodes as 15 and the validator refuses it ("at most 10 m"); it is
never forced onto "five".

**Loan numerals in Vosk.** The hi and te models lack some English teens and
tens (एटीन, థర్టీన్ …). A partial loan set can force "thirteen" onto త్రీ (3),
so loan numerals are all in or all out. hi and te set
`vosk.loan_numerals: false`, and their native 0–100 are complete. The CTC
lexicons keep the loans.

## Spoken numbers

0.4.0 (spec 2026-10-15, operator decision "Number words") reads spoken
decimals and halves on the phone, the idea of Python `word2number` done in
Kotlin from these tables (`command/SpokenNumbers.kt`; the app has no Python):

| Spoken | Value | Table |
|---|---|---|
| "two point five", "point five", "twenty point five" | 2.5, 0.5, 20.5 | en `numerals.decimal_point` + digit words 0–9 |
| "two and a half", "one and half" | 2.5, 1.5 | en `numerals.and_half` |
| "half a metre", "a half meter" | 0.5 | en `numerals.fractions` |
| डेढ़, ढाई, आधा मीटर; ఒకటిన్నర, రెండున్నర, అర మీటర్; ஒன்றரை … | 1.5, 2.5, 0.5 | `numerals.fractions` (provisional) |

- The step parser turns each of these into digits before it reads the clause
  ("move two point five meters left" is read as "move 2.5 meters left"). Plain
  integers stay words, so v0.2.0 messages that quote them are unchanged.
- A half of value 0.5 with no decimal point (half, आधा, అర, సగం, அரை …) counts
  only right before a metre word: "move forward half" is still refused. A half
  directly after "and" is not read ("2 meters and a half" and the typed
  "2 and a half" keep their v0.2.0 refusal: say 2.5).
- Every range rule stays: "twenty three meters" and "ten point five" are read,
  then refused (0 < distance <= 10 m, takeoff 1–10 m). Negation, negative
  numbers and other units keep their messages.
- The Vosk grammars hold the forms below `spoken_numbers.below`: gen.py adds
  "<n> point <d>", "point <d>" and "<n> and a half" numerals for n 0–9 (1–9 for
  the half) and d 1–9 to the en lexicon. Their word pairs ("two point",
  "point five", "and a") reach the grammar through `{num}`.
- hi दशमलव, te దశాంశం and ta புள்ளி stay scale words: no native speaker has
  confirmed them as a spoken decimal point (te దశాంశం is not in the te Vosk
  model).
- A spoken decimal or half counts only in the vocabulary that reads the step,
  as whole numerals since v0.2.0 ("move तीन meters forward" is refused):
  "move डेढ़ meters forward", "take off to ढाई meters" and "two point five
  मीटर आगे जाओ" are refused (say a plain number). Digits mix freely as before
  ("take off to 3 मीटर").
- The CTC lexicons have the halves as `slots.num` values.

## Natural phrasing

0.4.0 adds English phrasings the v0.2.0 rules did not read. The step parser
(`command/NaturalPhrasing.kt`) rewrites a clause before the
rules read it, and only when the clause names a number:

- `natural.direction_verbs`, without a move verb in the clause: a move in that
  direction. "climb 2 m" is "move up 2 m", "back up 2 m" is "move backward
  2 m", "ahead 2 m" is "move forward 2 m". When the clause also names that same
  direction ("climb up 2 m", "descend down 2 m"), only the verb is added. With a
  move verb the word keeps its v0.2.0 reading, so "go back up 2 m" is still two
  directions, and refused.
- `natural.comparatives`, after a move verb: the direction. "go higher 2 m" is
  "go up 2 m", "fly lower by 2 m" is "fly down by 2 m".
- Two of these phrases, or one beside another direction, are two directions:
  the clause is not rewritten and is refused ("climb down 2 m", "climb back
  up 2 m", "climb two meters back up", "climb rise 2 m": say one direction per
  step). The "up" of "back up" belongs to that phrase, so "climb back up 2 m"
  names up and backward and is never read as either.
- "climb to 3 m" names a height, not a distance, and is refused as before
  for up and down ("say how far to move").

| Direction | Direction verbs | Comparatives |
|---|---|---|
| forward | advance, ahead | |
| backward | reverse, retreat, back up | |
| up | climb, ascend, rise | higher |
| down | descend, drop, lower | lower |

"to the left", "towards the right" and "up by 2 m" were already read by the
v0.2.0 rules. 0.4.0 adds them to the en Vosk grammar as templates, with
`{side}` (the left and right forms of `{dir}`; "to the up" would be refused),
`{dirverb}` and `{comparative}`. Only the rule parser reads these classes, so
the generator refuses them in a CTC language's templates.

## Negation in the grammar

Spec 4.8 puts the negations in the grammar so that "don't take off" is decoded
and refused, and never heard as "take off". W1 found that single-word negation
phrases are not enough. A Vosk grammar is a bigram LM. With the negation only as
a single word, "don't take off" is reachable only through backoff, and W1 saw
Vosk drop the negation: in hi n-best (Δ ≈ 1.2–1.5) and in a te final result.

So every Vosk grammar also holds **negation pairs**. A negator stands right
before and right after each *command position* of every template:
- A command position is a verb-like class (`{takeoff}`, `{land}`, `{hold}`,
  `{stop}`, `{verb}`, `{status}`) or a literal word (करो, చేయి, to).
- `{num}`, `{dir}` and `{unit}` are arguments, so negators sit next to them only
  when the argument comes right before a command.

Examples:

| Language | Covered phrases |
|---|---|
| en | "don't take off", "do not land" |
| hi | "आगे मत जाओ", "लैंड मत करो", "लैंड करो मत" |
| te | "ల్యాండ్ వద్దు", "హోవర్ చేయి వద్దు" |

A negated command then costs the LM no more than the plain one, so the audio
decides. The parser refuses every grammar negation, and a test checks this.
Most are refused as negations: a negator word, a negator suffix ("n't") or a
fused negator. Some te negative imperatives are refused another way (see
below).

**Measured (2026-10-14).** `vosk_eval.py negation` decodes the W1 eSpeak clips
plus extra phrases with each grammar, with and without the negation pairs,
using desktop Vosk 0.3.45. It counts how often the 1-best keeps the negation:

| Language | Negated, without pairs | Negated, with pairs | Plain commands kept, without → with pairs |
|---|---|---|---|
| en | 2/10 | 9/10 | 19/20 → 18/20 |
| hi | 0/10 | 7/10 | 15/18 → 16/18 |

The en plain-command difference is one "hold" clip that decodes wrong both
ways ("toward" without the pairs, empty with them).

Without the pairs, "do not take off" decoded as "take off" and
"तीन मीटर आगे मत जाओ" as "तीन मीटर आगे जाओ". Those are valid plans. In the
remaining hi misses, the negated reading is the second alternative within
about 1 score point, so the n-best margin rule (T10) rejects the utterance.
te gives no signal, because the te model decodes these eSpeak clips as empty
both ways, as in W1.

- **Which negators get pairs.** `vosk.negators` lists them when it is set: en
  uses don't, do not, never, not, no, cannot, can't and won't. Otherwise every
  Vosk-usable negation of the lexicon gets pairs: hi मत, नहीं, नही, ना, न, मना;
  te వద్దు, చేయకు, చేయకండి, చెయ్యకు, చెయ్యకండి, కాదు, లేదు and the negative
  imperatives below.
- **Single words.** The other negations stay as single-word phrases.
- **"do not".** The en "do not" (spec 4.8) is a `grammar_negators` entry, so
  "do" is no longer an en filler.
- **Fixtures.** The `negated` fixtures cover these placements. en puts the
  negator before the command. hi and te put it after the last command word and
  before it. te also replaces the verb with its negative form ("ముందుకు
  వెళ్ళకు"). The parser must refuse every one.

**Telugu negative imperatives.** Telugu fuses "don't" onto the verb: వెళ్ళకు
and వెళ్ళవద్దు ("don't go"), పంపకండి, దిగకూడదు, చేయవద్దు. Without these words
in the grammar, "రెండు మీటర్లు ముందుకు వెళ్ళకు" could only decode as grammar
words, for example as the valid move "… ముందుకు వెళ్ళు". The te
`grammar_negators` table therefore holds every negative form of the te
command verbs that the te model can emit (20 words, provisional until a
native speaker reviews them). They get negation pairs like the other negators.

The v2 parser refuses each form in one of two ways:
- The -వద్దు and -ొద్దు forms end with a `rules.negator_suffixes` entry, so
  they are refused as negations.
- The -కు, -కండి, -కూడదు and -ద్దు forms are read by no parser table, so the
  clause has no rule reading. v0.2.0 sent such a clause to its AI fallback
  (`ai_required`). v2 has no AI fallback, and the golden contract (W1) says
  the port must reject every `ai_required` clause.

A Dart probe of all 20 forms in 6 contexts (2026-10-14) found no rule reading
for any of them. 39 cases returned `ai_required`, and the rest were refused as
a negation or as "is not understood". No golden vector contains any of these
words.

The Kotlin parser (T10) must not add `grammarNegators` to the
transcript-level negators. That would change golden error messages:
"ల్యాండ్ చేయొద్దు" expects the step-level "negated commands are never sent".
Treating the forms that no rule table reads as step-level negations changes
no golden vector, so T10 may do that as extra hardening.

"Don't stop" (ఆపకు, ఆపొద్దు, ఆగవద్దు) is left out on purpose. In the grammar it
could absorb a spoken ఆపు, while "don't stop" heard as STOP costs only a benign
hover (spec 4.12).

The ta, kn and ml CTC lexicons list every negation word and suffix of their
lexicon; any of them rejects the clause.

## Plans

v0.2.0 plans hold one to five commands joined by connectives ("move forward
two meters and then land", "दो मीटर आगे जाओ फिर लैंड करो"). Inside a template
the grammar has its own word pairs. Without more pairs, the step from one
command to the next is reachable only through backoff. So the grammar also
holds **connective pairs**:
- the last word of every template before the first word of every connective
  ("meters and", "जाओ फिर");
- the last word of every connective before the first word of every template
  ("then land", "फिर लैंड", "తర్వాత ల్యాండ్").

**Measured (2026-10-14, `vosk_eval.py plans`).** The test set is 2-step plans,
a move and a land joined by each connective, with 3 voices at 2 speeds. The
"2 steps" count is how often the 1-best keeps a connective right before the
second command:

| Language | 2 steps, without pairs | 2 steps, with pairs | Single commands exact, without → with pairs |
|---|---|---|---|
| en | 7/30 | 25/30 | 20/36 → 20/36 |
| hi | 13/30 | 25/30 | 7/30 → 6/30 |

Without the pairs, "… meters then land" decoded as "… meters land" and
"… आगे जाओ फिर लैंड करो" as "… आगे जाओ". The 2-step check counts any connective,
so "then" decoded as "and" counts as kept. No plan was decoded word for word,
with or without the pairs: eSpeak's first word is often lost. te gives no
signal here either.

## Stop lane and keyword spotting

**Decision (T9, 2026-10-14, on the W1 finding).** The keyword spotter listens for "stop"
only: `kws.keywords: [stop]`. This is `kws_keywords` per language in
`grammar_manifest.json` and `GrammarTables.KWS_KEYWORDS`. "hold" is not a
keyword, and "hover" is not a STOP or "hold" synonym anywhere in the STOP lane.

**Why.** On the W1 eSpeak clips, the sherpa KWS:
- fired "hold" on the loanword "hover" (ta, kn, ml and hi);
- in the zh-en model, also fired "hold" on kn ಹೋಗು and ml പോകൂ (both "go", a
  move verb).

A spotter "hold" would therefore turn ordinary hover or move commands into
STOPs.

- **HOLD stays a push-to-talk grammar command.** In the parser, "hover", "hold"
  and "hold position" are the v0.2.0 HOVER (hold) command, unchanged. A HOLD
  executes without confirmation, like before.
- **The STOP lane.** `stop_lane` per language is spec 7.5 without hold words:
  en stop; hi रुको, रुक जाओ, स्टॉप; te ఆపు, ఆగు, స్టాప్; ta நில், நிறுத்து,
  ஸ்டாப்; kn ನಿಲ್ಲು, ನಿಲ್ಲಿಸು, ಸ್ಟಾಪ್; ml നിർത്തൂ, നിൽക്കൂ, സ്റ്റോപ്പ്.
- **Deviation from spec 7.5.** Spec 7.5 also lists "hold" for en. It is left out
  so that a spoken "hold" means HOLD, never the stronger STOP lane (BRAKE, or
  disarm on the ground). A test enforces that no stop-lane phrase is a hold or
  hover form.
- **Near-stop words.** Spec 4.12 wants a lenient spotter, because a false
  STOP while the app owns the mode is a benign hover. So `stop_<lang>.json`
  holds no decoy that sounds like a stop word, and no filler may be one either.
  A word is near-stop if it is listed in `vosk.near_stop` or is an inflection
  of a stop-lane word (stops, stopped, रुके). Current lists:
  - en: start, started, step, steps, stuff, top, and (0.4.0) the down verb drop;
  - hi: रूप (one sound from रुक);
  - te: ఆరు (6) and the loan అప్ (up), each one sound from ఆపు or ఆగు, and
    స్టార్, one sound from స్టాప్.

  The command grammar keeps its lexicon words (step and steps are counted
  units, refused by the parser). A test pins the lists.
- **Measured (2026-10-14, `vosk_eval.py stop`).** The spotter rule is spec
  4.12's: a stop token in the partial on 2 consecutive 100 ms steps, or in the
  final result. The test set is 6 eSpeak voices at 3 speeds:

  | en grammar | STOP caught | False STOP |
  |---|---|---|
  | `stop_en.json` | 53/54 | 11/144 |
  | with the near-stop decoys | 51/54 | 4/144 |

  As decoys, "start" took spoken "stop"s ("stop" read as "start", "stop the
  drone" as "start long"). Every extra false STOP is "start the engine",
  which is accepted as a benign hover.

  hi gives no signal. eSpeak's "रुको" is decoded as "उठो" or junk even without
  a grammar, and रूप changes nothing (24/48 caught either way). te is mostly
  empty, as in W1. Real speakers are needed (spec 10.2).
- **Keyword lines.** `kws.models` holds the keyword lines per spotter model.
  gigaspeech 3.3M chunk-16 is the default (`▁ST O P @stop`). zh-en 3M chunk-8
  is optional and off: its licence is unconfirmed.
- **Native-language spotting.** A spotter for ta, kn and ml arrives with Phase
  2.5. Until then, those languages' STOP words are recognised only after
  push-to-talk release (spec 4.12). The STOP button and Volume-Down always work.

## Fillers

Spec 4.6 adds 200–2,000 frequent non-command words as single-word phrases. Then
out-of-grammar speech decodes as fillers or `[unk]` instead of being forced onto
a command. W1 showed the need: "take a photo of the field" decoded as "take off".

**Source.** The spec names a public frequency list. Each Vosk model's own LM is
a frequency list of its training text, already limited to its vocabulary. So
the generator ranks the unigram probabilities in the model's `graph/Gr.fst` (an
OpenFst NGramFst) instead, with no download. `--suggest-fillers <lang> --count
300` prints the most frequent words, skipping these:
- lexicon words;
- `vosk.filler_exclude` and the near-stop words (`vosk.near_stop`, see "Stop
  lane and keyword spotting");
- non-NFC spellings and words outside the script;
- any word that starts with a stem of a stop-lane or hold form, such as
  stops, stopped, hovering or रुके. A STOP or HOLD must never be absorbed by a
  filler.

`vosk.fillers` is exactly that output for 300 words: en 300, hi 300, te 300. A
test checks it while the models are on disk. The generator also refuses a
near-stop filler, so a hand edit cannot bring "start" back.

**`filler_exclude`** holds the other acoustic neighbours and spelling variants
found by review:
- en: over, old and whole, near hover and hold;
- te: numeral variants such as అయిదు and సున్న;
- hi: numeral variants such as अट्ठारह and चौबिस, so the spoken number decodes
  as the numeral.

**Changing the list.** To refresh it after a model or lexicon change, run the
suggestion and paste it over `vosk.fillers`. When the tables change, the test
names the difference.

## Code-mixing and loanwords

Code-mixing comes from the grammar union, not from language ID (spec 7.3).
Each Indic lexicon has a `loanwords` table:
- the English command words in its script (టేకాఫ్ already is a rule form; the
  table adds టేక్ ఆఫ్, లాంచ్, ఫార్వర్డ్, మీటర్స్ …);
- each entry's template class;
- the native form the normaliser maps it to;
- `latin`, its Latin spelling (డ్రోన్ ↔ drone style two-way map).

Loanwords join their class in the templates and the CTC lexicons. The Vosk
hi and te models drop Latin words, so their grammars hold only native-script
forms. hi अप ("up") is left out on purpose: it is one vowel away from the
filler आप ("you").

## Flight memory

Spec 2026-10-28 adds five commands that fly back to positions this app flew the
vehicle to and telemetry confirmed. They name no coordinates: the service
resolves them from its own flight memory when the step starts, and validates
them like moves (30 m from home, 1–10 m high).

| Intent | Tool | en | hi | te |
|---|---|---|---|---|
| return_home | return_home | return home, come back home, go home | घर वापस जाओ, होम पर वापस जाओ | ఇంటికి తిరిగి రా, హోమ్ కి తిరిగి రా |
| go_back | go_back | go back, undo, previous position | वापस जाओ, पिछली जगह पर जाओ | మునుపటి చోటుకి వెళ్ళు |
| retrace | retrace | retrace the path, go back the same way | उसी रास्ते से वापस जाओ | అదే దారిలో తిరిగి రా |
| goto_point | goto_point(point) | go to point 2 | पॉइंट दो पर जाओ | పాయింట్ రెండు దగ్గరకు వెళ్ళు |
| mark_point | mark_point(point) | mark point 2, mark this as point 2 | पॉइंट दो मार्क करो | పాయింట్ రెండు మార్క్ చేయి |

- The phrases are the lexicon's `memory` tables. Each is a template class:
  `{home}`, `{goback}`, `{retrace}`, `{point}` (the noun before a point
  number), `{goto}`, `{mark}` and `{markas}` (en "mark this as"). hi and te put
  the point number before the verb, as its own word ("పాయింట్ రెండు దగ్గరకు
  వెళ్ళు"), because the CTC lexicon reads numbers only as whole words.
- `return_home` is not ArduPilot RTL, which stays disabled in
  `intents.v1.yaml`. It is a level GUIDED flight to home's north and east at
  the current height, confirmed once; land is its own step.
- "spot" is never a form. It is one sound from "stop", so in a recognizer
  grammar it could absorb a spoken STOP. The saved positions are "points"
  everywhere, matching the tool names.
- The command words leave the Vosk filler lists: en "home", "this", "as",
  "same", "way", "last", "remember", "number" and "come"; hi घर and जगह; te
  అదే, కి, తిరిగి and వచ్చిన.
- The rule parser (`command/MemoryCommands.kt`) reads a clause as a memory
  command only when the whole clause is one of these templates, courtesy and
  filler words aside. So "go back 3 m" stays a backward move, "land at home" is
  still refused, and a negation ("don't go back") never matches and is
  refused as before. The CTC template matcher reads the te templates like any
  other.
- The CTC template matcher counts a dropped fixed word as one edit, so a te
  phrase may not be another intent's phrase minus one word: "తిరిగి వెళ్ళు"
  (go back) is left out, since it is "ఇంటికి తిరిగి వెళ్ళు" (return home)
  without its first word and the margin would refuse both.
- The hi and te phrases are provisional (native-speaker review). ta, kn and ml
  have no memory phrases yet: their speakers use the flight memory buttons.
- No phrase has been checked against the Vosk model vocabularies here (the
  models are not on this machine). Run `gen.py --require-vosk` with the models
  before a release and move any missing word to `vosk.exclude` or a better
  form.

## Kotlin

`GrammarTables.kt` (package `com.aeronex.app.grammar`) has no
dependencies:

- **`GrammarTables`** holds these values:
  - `VERSION` and `SOURCE_SHA256`;
  - `LANGUAGES`, `RULE_LANGUAGES`, `VOSK_LANGUAGES`, `CTC_LANGUAGES` and
    `DIRECTIONS`;
  - `RULE_COMMANDS` (v0.2.0 command id to intent) and `KWS_KEYWORDS`;
  - `lexicons` and `lexicon(code)`, which throws for an unknown code.
- **`GrammarTables.Plan`** holds the `plan_interpreter.dart` tables, joined
  over the languages in source order. Examples: `CONNECTIVES` (in match order),
  `FIRST_WORDS`, `COURTESY`, `MOTION_VERBS`, `SCALE_WORDS`, `NUMERAL_WORDS`,
  `TAKEOFF_WORDS`, `SIMPLE_WORDS`, `LAND_WORDS`, `INDIC_FILLER`,
  `CONTEXT_FILLERS`, `LIGHT_VERB_*`, `CORRECTION_*`, `WAKE_*` and
  `SCRIPT_TIE_ORDER`. Each field's KDoc names its Dart original.
- **`GrammarTables.Transcript`** holds the `command_transcript.dart` tables:
  `NEGATORS`, `FUSED_NEGATORS` (match order), `NEGATION_WORDS`,
  `NEGATION_APOSTROPHES`, `NUMBERS`, `NUMBER_PRECEDERS`, `SPELLING`,
  `HOLD_ALIASES`, `BARE_TAKEOFF`, `MISHEARINGS` and `FOREIGN_SCRIPTS`.
- **`Lexicon`** is one language. It has:
  - the rule tables, verbatim and in source order (`moveVerbs`,
    `directions`, `units`, `commands`, `negators`, `ones`/`tens`/`numbers`,
    `units19`);
  - `grammarNegators`, `stopLane`, `kwsKeywords` and `numerals` (`Numeral(phrase,
    value, NumeralKind)`);
  - `loanwords` (`Loanword(loan, latin, wordClass, native)`);
  - `templates` (`Template(intent, tokens)`, optional tokens expanded);
  - `classes` (class to forms, loans included; 0.4.0 adds `side`, `dirverb`
    and `comparative`);
  - `decimalPoints`, `andHalf`, `directionVerbs` and `comparatives` (0.4.0,
    see "Spoken numbers" and "Natural phrasing");
  - `provisionalWords`, plus the script range and the digit zero.

Lists keep source order, which the Dart matching order depends on. Sets are
written sorted. Characters such as ZWNJ are `\u` escapes.
`app/android/app/src/test/kotlin/.../grammar/GrammarTablesTest.kt` checks that
the tables, the assets and the fixtures come from one generator run, and that
the safety rules above hold.

## Adding a language

Follow spec 7.8:

1. Add `lexicon/<lang>.yaml`: script, rule-style tables, numerals 0–100 with
   halves, loanwords, connectives, negations, stop lane and templates. Mark
   everything `provisional: true` until a native speaker reviews it.
2. Add the code to `languages` and, for a recognizer, to `engines`.
3. For Vosk, add `vosk.fillers` from `--suggest-fillers`.
4. Run `python tools/grammar-gen/gen.py --require-vosk`. The run fails if a
   command word is missing from the model.
5. Run `python -m pytest tools/grammar-gen -q` and the JVM `GrammarTablesTest`.

No code outside this folder and the generated files should need to change.
