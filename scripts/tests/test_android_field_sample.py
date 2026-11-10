import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from android_field_sample import parse_battery, parse_pss_kb


class AndroidFieldParsingTest(unittest.TestCase):
    def test_modern_android_pss_and_battery(self):
        mem = "  TOTAL PSS:  53,222   TOTAL RSS: 150000  TOTAL SWAP PSS: 0"
        self.assertEqual(53222, parse_pss_kb(mem))
        self.assertEqual({"level_percent": 64, "temperature_tenths_c": 305},
                         parse_battery("  level: 64\n  temperature: 305\n"))

    def test_older_android_total_pss(self):
        self.assertEqual(45230, parse_pss_kb("   TOTAL   45230  3333   5555"))

    def test_missing_data_does_not_invent_readings(self):
        self.assertIsNone(parse_pss_kb("No process found"))
        self.assertEqual({"level_percent": None, "temperature_tenths_c": None},
                         parse_battery("Battery state unknown"))


if __name__ == "__main__":
    unittest.main()
