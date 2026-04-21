// How to run:
// echo 'CheckCfp.main();/exit' | jshell --startup .github/scripts/check_cfp.java

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

class CheckCfp {

    static final String GREEN_BALL = "\uD83D\uDFE2"; // 🟢

    static final Map<String, Integer> MONTHS = Map.ofEntries(
        Map.entry("january",   1),
        Map.entry("february",  2),
        Map.entry("march",     3),
        Map.entry("april",     4),
        Map.entry("may",       5),
        Map.entry("june",      6),
        Map.entry("july",      7),
        Map.entry("august",    8),
        Map.entry("september", 9),
        Map.entry("october",  10),
        Map.entry("november", 11),
        Map.entry("december", 12)
    );

    // Matches: (Closes/Closed [DD] MonthName YYYY)
    static final Pattern CFP_DATE_RE = Pattern.compile(
        "\\(Clos(?:es|ed)\\s+(?:(\\d{1,2})\\s+)?" +
        "(January|February|March|April|May|June|July|August" +
        "|September|October|November|December)" +
        "\\s+(\\d{4})\\)",
        Pattern.CASE_INSENSITIVE
    );

    static LocalDate parseDate(String dayStr, String monthStr, String yearStr) {
        int year  = Integer.parseInt(yearStr);
        int month = MONTHS.get(monthStr.toLowerCase());
        int day   = (dayStr != null && !dayStr.isEmpty())
                    ? Integer.parseInt(dayStr)
                    : YearMonth.of(year, month).lengthOfMonth();
        return LocalDate.of(year, month, day);
    }

    static String updateLine(String line, LocalDate today) {
        Matcher m = CFP_DATE_RE.matcher(line);
        if (!m.find()) return line;

        LocalDate cfpDate = parseDate(m.group(1), m.group(2), m.group(3));
        boolean isOpen   = !cfpDate.isBefore(today);
        String  before   = line.substring(0, m.end());
        String  after    = line.substring(m.end());
        boolean hasGreen = after.contains(GREEN_BALL);

        if (isOpen && !hasGreen) {
            return before + " " + GREEN_BALL + after;
        } else if (!isOpen && hasGreen) {
            return before + after.replace(" " + GREEN_BALL, "").replace(GREEN_BALL, "");
        }
        return line;
    }

    public static void main() throws IOException {
        Path   readme  = Path.of("README.md");
        String content = Files.readString(readme, UTF_8);
        // Split preserving line endings by keeping the delimiter
        String[] lines   = content.split("\n", -1);
        LocalDate today  = LocalDate.now();
        boolean changed  = false;

        var sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String updated = updateLine(lines[i], today);
            if (!updated.equals(lines[i])) changed = true;
            sb.append(updated);
            if (i < lines.length - 1) sb.append('\n');
        }

        if (changed) {
            Files.writeString(readme, sb.toString(), UTF_8);
            System.out.println("README.md updated with current CFP statuses.");
        } else {
            System.out.println("No changes needed.");
        }
    }
}
