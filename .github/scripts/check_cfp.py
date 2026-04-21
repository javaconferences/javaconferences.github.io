#!/usr/bin/env python3
"""
Check CFP dates in README.md and add/remove the green ball emoji (🟢)
based on whether the CFP deadline is today or still in the future.

Patterns matched (case-insensitive):
  (Closes DD MonthName YYYY)
  (Closed DD MonthName YYYY)
  (Closes MonthName YYYY)   ← no day: last day of month is assumed
  (Closed MonthName YYYY)
"""

import re
from calendar import monthrange
from datetime import date

MONTHS = {
    "january": 1,
    "february": 2,
    "march": 3,
    "april": 4,
    "may": 5,
    "june": 6,
    "july": 7,
    "august": 8,
    "september": 9,
    "october": 10,
    "november": 11,
    "december": 12,
}

# Matches: (Closes/Closed [DD] MonthName YYYY)
CFP_DATE_RE = re.compile(
    r"\(Clos(?:es|ed)\s+"
    r"(?:(\d{1,2})\s+)?"
    r"(January|February|March|April|May|June|July|August"
    r"|September|October|November|December)"
    r"\s+(\d{4})\)",
    re.IGNORECASE,
)

GREEN_BALL = "\U0001f7e2"  # 🟢


def parse_date(day_str, month_str, year_str):
    year = int(year_str)
    month = MONTHS[month_str.lower()]
    day = int(day_str) if day_str else monthrange(year, month)[1]
    return date(year, month, day)


def update_line(line, today):
    """Return (new_line, changed)."""
    m = CFP_DATE_RE.search(line)
    if not m:
        return line, False

    cfp_date = parse_date(*m.groups())
    is_open = cfp_date >= today
    end = m.end()
    after = line[end:]
    has_green = GREEN_BALL in after

    if is_open and not has_green:
        new_line = line[:end] + " " + GREEN_BALL + after
        return new_line, True
    elif not is_open and has_green:
        new_after = after.replace(" " + GREEN_BALL, "").replace(GREEN_BALL, "")
        return line[:end] + new_after, True

    return line, False


def main():
    readme = "README.md"
    with open(readme, encoding="utf-8") as f:
        lines = f.read().splitlines(keepends=True)

    today = date.today()
    changed = False
    new_lines = []
    for line in lines:
        new_line, line_changed = update_line(line, today)
        new_lines.append(new_line)
        if line_changed:
            changed = True

    if changed:
        with open(readme, "w", encoding="utf-8") as f:
            f.writelines(new_lines)
        print("README.md updated with current CFP statuses.")
    else:
        print("No changes needed.")


if __name__ == "__main__":
    main()
