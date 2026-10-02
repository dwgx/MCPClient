"""The signing ceremony's classpath must be built from the JVM's own separator.

**Why this test exists.** The fix for "the signing ceremony cannot run on Windows" was
committed as `d5a82b6`. It is real and it works. It also had **no regression test**: nothing in
the repository read `scripts/sign-patch.sh`, so reverting the fix would have left every suite
green. That was found by the worker writing `docs/agency/guarantees.md` -- while producing the
tenth instance of this session's own failure shape inside the document whose purpose is to stop
it. See that document's section 1.

**What the fix does, and what each part is load-bearing for.**

1. The separator is *asked of the JVM* rather than hardcoded. `mvn dependency:build-classpath`
   already writes the platform separator into its cache file, so joining its entries with `:`
   produces a classpath the JDK rejects with "package does not exist" -- on Windows only, and
   only at signing time, which is the worst possible moment because the ceremony that is the
   authority on signatures is the thing that cannot run.
2. The output is passed through `tr -d '\\r'`. Windows JDK output is CRLF and command
   substitution strips only the `\\n`, so the extracted separator arrives as `";\\r"` and
   silently corrupts the classpath. **The first version of the fix shipped with exactly this
   defect**, and only running the extraction revealed it -- reading the script did not.

These assertions are about the *script's text*, deliberately. They are a contract on the shape of
the fix, not a re-implementation of it: the expensive property (the ceremony actually signing) is
verified by running the ceremony, and the cheap property (the script no longer hardcodes a
separator and does not reintroduce a stray CR) is verified here, so a revert cannot pass silently.
"""

import io
import os
import re
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sign-patch.sh")


def script_text():
    with io.open(SCRIPT, encoding="utf-8") as handle:
        return handle.read()


class TheSigningClasspathIsBuiltFromTheJvmTest(unittest.TestCase):
    """Contract on `sign-patch.sh`. Each assertion names the defect it prevents."""

    def setUp(self):
        self.text = script_text()

    def test_the_script_exists_and_is_readable(self):
        # Without this the other tests would pass vacuously on an empty string.
        self.assertGreater(len(self.text), 0, "sign-patch.sh read as empty")
        self.assertIn("signing", self.text, "this does not look like the signing script")

    def test_the_separator_is_asked_of_the_jvm_not_hardcoded(self):
        """Prevents the original defect: a hardcoded ':' breaks the ceremony on Windows."""
        self.assertIn(
            "-XshowSettings:properties",
            self.text,
            "the script must ask the JVM for its classpath separator; asking is the only thing "
            "that is correct on every platform, and a hardcoded guess is what shipped broken",
        )
        self.assertRegex(
            self.text,
            r"path\\?\.separator",
            "the script must read path.separator out of the JVM's own properties",
        )

    def test_no_classpath_join_hardcodes_a_colon(self):
        """The precise failure: joining the cached classpath with ':' on a Windows JDK."""
        # Any remaining `"...:..."` directly inside a -cp argument or a CP= assignment is the bug.
        offenders = [
            line.strip()
            for line in self.text.splitlines()
            if re.search(r'(CP=.*:|-cp\s+"?\$?\{?[A-Z_]*:?\$\{?[A-Za-z_]+\}?:"?\S*:)', line)
        ]
        self.assertEqual(
            offenders,
            [],
            "a classpath join still hardcodes ':':\n  " + "\n  ".join(offenders),
        )

    def test_both_join_sites_use_the_derived_separator(self):
        """One spelling means a future join site cannot forget the other."""
        self.assertGreaterEqual(
            self.text.count("$CP_SEP"),
            3,
            "CP_SEP must be used at both join sites plus its assignment; found "
            f"{self.text.count('$CP_SEP')} occurrences, which means a site was missed",
        )

    def test_the_carriage_return_is_stripped(self):
        """Prevents the defect the FIRST version of this fix shipped with.

        `$(...)` strips a trailing newline but not a carriage return, so on a Windows JDK the
        extracted separator is ";\\r" and the classpath is silently wrong. This was only caught
        by running the extraction.
        """
        self.assertIn(
            "tr -d '\\r'",
            self.text,
            "the JVM's output must be stripped of CR before the separator is parsed, or Windows "
            "yields a separator with a stray carriage return on it",
        )

    def test_the_separator_is_not_left_unvalidated(self):
        """An empty extraction must fail loudly rather than produce a classpath of ':'."""
        self.assertIn(
            "could not determine the JVM classpath separator",
            self.text,
            "an unparseable separator must abort the ceremony, not silently produce a broken "
            "classpath -- a silent failure here means unsigned patches, which is the one outcome "
            "worse than a loud one",
        )


if __name__ == "__main__":
    unittest.main()
