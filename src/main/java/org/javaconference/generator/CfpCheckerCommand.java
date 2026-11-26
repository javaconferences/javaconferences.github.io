package org.javaconference.generator;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * CLI command that re-scans the CFP dates in {@code README.adoc} and toggles each one from
 * (Closes ...) to (Closed ...), adding/removing the green ball, once its close date passes.
 * Equivalent to {@code mvn clean process-classes -Dcommand=check-cfp}.
 */
@DefaultScoped
@Command(name = "check-cfp", description = "Update CFP open/closed statuses in the AsciiDoc source.")
public class CfpCheckerCommand implements Runnable {
    private static final String GREEN_BALL = "\uD83D\uDFE2"; // 🟢

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("january", 1), Map.entry("february", 2), Map.entry("march", 3),
            Map.entry("april", 4), Map.entry("may", 5), Map.entry("june", 6),
            Map.entry("july", 7), Map.entry("august", 8), Map.entry("september", 9),
            Map.entry("october", 10), Map.entry("november", 11), Map.entry("december", 12));

    // Matches: (Closes/Closed [DD] MonthName YYYY)
    private static final Pattern CFP_DATE_RE = Pattern.compile(
            "\\(Clos(?:es|ed)\\s+(?:(\\d{1,2})\\s+)?" +
                    "(January|February|March|April|May|June|July|August" +
                    "|September|October|November|December)" +
                    "\\s+(\\d{4})\\)",
            Pattern.CASE_INSENSITIVE);

    private final Config config;
    private final Logger logger = Logger.getLogger(CfpCheckerCommand.class.getName());

    public CfpCheckerCommand(final Config config) {
        this.config = config;
    }

    @Override
    public void run() {
        try {
            update(Path.of(config.source()));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void update(final Path source) throws IOException {
        final var content = Files.readString(source, UTF_8);
        final var lines = content.split("\n", -1);
        final var today = LocalDate.now();
        final var sb = new StringBuilder();
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            final var updated = updateLine(lines[i], today);
            changed |= !updated.equals(lines[i]);
            sb.append(updated);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        if (changed) {
            Files.writeString(source, sb.toString(), UTF_8);
            logger.info(() -> source + " updated with current CFP statuses.");
        } else {
            logger.info(() -> "No changes needed.");
        }
    }

    private static LocalDate parseDate(final String dayStr, final String monthStr, final String yearStr) {
        final int year = Integer.parseInt(yearStr);
        final int month = MONTHS.get(monthStr.toLowerCase());
        final int day = (dayStr != null && !dayStr.isEmpty())
                ? Integer.parseInt(dayStr)
                : YearMonth.of(year, month).lengthOfMonth();
        return LocalDate.of(year, month, day);
    }

    private static String updateLine(final String line, final LocalDate today) {
        final Matcher m = CFP_DATE_RE.matcher(line);
        if (!m.find()) {
            return line;
        }
        final LocalDate cfpDate = parseDate(m.group(1), m.group(2), m.group(3));
        final boolean isOpen = !cfpDate.isBefore(today);
        final String before = line.substring(0, m.end());
        final String after = line.substring(m.end());
        final boolean hasGreen = after.contains(GREEN_BALL);

        if (isOpen && !hasGreen) {
            return before + " " + GREEN_BALL + after;
        } else if (!isOpen && (hasGreen || before.contains("Closes"))) {
            return before.replace("Closes", "Closed") + after.replace(" " + GREEN_BALL, "").replace(GREEN_BALL, "");
        }
        return line;
    }

    @RootConfiguration("check-cfp")
    public record Config(
            @Property(value = "source", defaultValue = "\"README.adoc\"", documentation = "AsciiDoc file whose CFP statuses to update.")
            String source) {
    }
}