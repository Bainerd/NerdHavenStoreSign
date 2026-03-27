package com.badlogic.NHSS;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**

 StoreHourScraper
 Fetches store hours (and notices/exceptions) from https://www.nerdhavenarcade.com/
 Behavior:
 Parses lines like "Thursday - 3pm -10pm", "Friday- Noon - 10pm", "Monday - Wednesday - Closed"
 Detects exception/notice lines (e.g. "Friday, March 27th 5pm to 7pm closed for a private event")
 and collects them separately so they do not overwrite canonical hours.
 Returns a String[] containing:
 [ "NERD HAVEN ARCADE",

 "STORE HOURS:",

 "MONDAY: ...",

 ...

 "SUNDAY: ...",

 "",               // optional separator if notices present

 "NOTICES:",

 "Friday: March 27th 5pm to 7pm closed for a private event",

 ...

 ]

 Notes:
 If you prefer a structured return type (e.g., an object with hours map + notices list),
 I can change the API instead of returning a flat String[].
 For best reliability, replace the searchArea extraction with a precise CSS selector
 if you can identify the container element for hours on the site (doc.select("...").text()).
 */
public class StoreHourScraper {
    private static final String[] DAYS = {

        "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY",
        "FRIDAY", "SATURDAY", "SUNDAY"
    };

    public static String[] fetchStoreHours() throws Exception {
        Document doc = Jsoup.connect("https://www.nerdhavenarcade.com/").get();

        // Prefer narrowing to a specific element if you know the selector.
        // Fallback to page text if not found.
        String searchArea;
        try {
            org.jsoup.nodes.Element hoursEl = doc.selectFirst("body");
            // wholeText() preserves the line breaks so the scraper can see each day individually
            searchArea = (hoursEl != null) ? hoursEl.wholeText() : doc.text();
        } catch (Exception e) {
            searchArea = doc.text();
        }

        // Use a per-line regex so weekday names must appear at the start of a line (safer).
        Pattern linePattern = Pattern.compile(
            "(?i)^\\s*(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)" +
                "(?:\\s*-\\s*(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday))?" +
                "\\s*-?\\s*(.*)$"
        );

        Map<String, String> dayMap = new HashMap<>();
        List<String> notices = new ArrayList<>();

        String[] lines = searchArea.split("\\r?\\n");
                for (String line : lines) {
            // Clean the line for the font IMMEDIATELY (removes ** and other symbols)
            String cleanedLine = line.replaceAll("[^a-zA-Z0-9\\s:]", " ").replaceAll("\\s+", " ").trim();

            Matcher m = linePattern.matcher(cleanedLine);
            if (!m.find()) continue;

            String day1 = m.group(1);
            String day2 = m.group(2);
            String remainder = m.group(3).trim();
            if (remainder.isEmpty()) continue;

            String lowerRem = remainder.toLowerCase(Locale.ROOT);

            // Skip ONLY actual addresses/contact info (don't skip dates/times)
            if (lowerRem.matches(".*\\b(drive|dr|rd|street|st|ave|lane|ln|way|blvd|ste|suite|wi|wisconsin|madison|monona|53716)\\b.*")
                || lowerRem.contains("contact") || lowerRem.contains("email") || lowerRem.contains("@")) {
                continue;
            }

            // If it's a notice (like "March 27th private event"), capture it
            if (looksLikeNotice(remainder)) {
                notices.add(normalizeNoticeText(day1, remainder));
                continue;
            }

// Strip ALL non-alphanumeric characters (including * , . - _) to support basic fonts
            remainder = remainder.replaceAll("[^a-zA-Z0-9\\s:]", " ").replaceAll("\\s+", " ").trim();
            if (remainder.isEmpty()) continue;


            // If the remainder doesn't look like hours and looks like a notice, treat it as notice.
            if (looksLikeNotice(remainder) && !remainder.toLowerCase(Locale.ROOT).matches(".*(\\bopen\\b|\\bclosed\\b|am|pm|noon).*")) {
                notices.add(normalizeNoticeText(day1, remainder));
                continue;
            }

            // Optional: require an explicit time token for hours to avoid false positives.
            // If the remainder doesn't contain am/pm/noon/closed, skip treating it as normal hours.
            if (!remainder.toLowerCase(Locale.ROOT).matches(".*(am|pm|noon|closed).*")) {
                // If it also looks like a notice, capture it as notice; otherwise skip.
                if (looksLikeNotice(remainder)) {
                    notices.add(normalizeNoticeText(day1, remainder));
                }
                continue;
            }

            String normalized = normalizeHoursText(remainder);

            if (day2 != null) {
                List<String> range = expandDayRange(day1.toUpperCase(Locale.ROOT), day2.toUpperCase(Locale.ROOT));
                for (String d : range) {
                    dayMap.put(d, normalized);
                }
            } else {
                dayMap.put(day1.toUpperCase(Locale.ROOT), normalized);
            }
        }

        // Build output array
        List<String> out = new ArrayList<>();
        out.add("NERD HAVEN ARCADE");
        out.add("STORE HOURS:");

        for (String day : DAYS) {
            String value = dayMap.getOrDefault(day, "CLOSED");
            if ("CLOSED".equals(value)) {
                out.add(day + ": CLOSED");
            } else {
                out.add(day + ": " + value);
            }
        }

        // Append notices if found
        if (!notices.isEmpty()) {
            int maxCharsPerLine = 21; // adjust to suit your board/font
            out.add(""); // separator
            out.add("NOTICES:");
            for (String n : notices) {
                appendWrappedNotice(out, n, maxCharsPerLine);
            }
        }

        return out.toArray(new String[0]);
    }

    // Case-insensitive indexOf helper
    private static int indexOfIgnoreCase(String src, String target) {
        return src.toLowerCase(Locale.ROOT).indexOf(target.toLowerCase(Locale.ROOT));
    }

    // Heuristic to decide whether a matched remainder is an exception/notice rather than normal hours
    private static boolean looksLikeNotice(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase(Locale.ROOT);

        // Triggers for events and closures found in recent updates
        return lower.contains("private") ||
            lower.contains("event") ||
            lower.contains("party") ||
            lower.contains("closed for") ||
            lower.contains("holiday") ||
            lower.contains("update") ||
            lower.contains("announcement") ||
            // Catch date patterns like "March 27th"
            lower.matches(".*\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\w*\\b.*") ||
            lower.matches(".*\\d{1,2}(st|nd|rd|th)\\b.*");
    }

    // Normalize notice text: remove surrounding asterisks, prefix with day for clarity if not already present
    private static String normalizeNoticeText(String day, String raw) {
        // Strip special characters immediately
        String clean = raw.replaceAll("[^a-zA-Z0-9\\s:]", " ").replaceAll("\\s+", " ").trim();

        if (!clean.toLowerCase(Locale.ROOT).startsWith(day.toLowerCase(Locale.ROOT))) {
            String dayPretty = day.substring(0, 1).toUpperCase() + day.substring(1).toLowerCase();
            return dayPretty + ": " + clean;
        }
        return clean;
    }

    // Normalize hour fragments into consistent formatting, e.g. "Noon - 10pm" or "3 pm - 10 pm"
    private static String normalizeHoursText(String raw) {
        if (raw == null || raw.equalsIgnoreCase("closed")) return "CLOSED";
        // Ensure final hours string is also font-safe
        return raw.replaceAll("[^a-zA-Z0-9\\s:]", " ").replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    // Expand day ranges like MONDAY -> WEDNESDAY into [MONDAY, TUESDAY, WEDNESDAY]
    // Expand day ranges like MONDAY -> WEDNESDAY into [MONDAY, TUESDAY, WEDNESDAY]
    private static List<String> expandDayRange(String start, String end) {
        List<String> range = new ArrayList<>();
        List<String> days = Arrays.asList(DAYS);
        int s = days.indexOf(start);
        int e = days.indexOf(end);
        if (s != -1 && e != -1) {
            for (int i = s; i <= e; i++) range.add(days.get(i));
        }
        return range;
    }

// Quick main for testing locally
    public static void main(String[] args) {
        try {
            String[] lines = fetchStoreHours();
            for (String l : lines) {
                System.out.println(l);
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch store hours: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Word-wrap a single string to lines no longer than maxChars.
     * Splits on whitespace, preserves words, and returns the wrapped lines.
     */
    private static List<String> wrapToMaxChars(String s, int maxChars) {
        List<String> lines = new ArrayList<>();
        if (s == null) return lines;
        s = s.trim();
        if (s.isEmpty()) return lines;
        String[] words = s.split("\\s+");
        StringBuilder cur = new StringBuilder();
        for (String w : words) {
            if (cur.length() == 0) {
                // start new line
                cur.append(w);
            } else if (cur.length() + 1 + w.length() <= maxChars) {
                cur.append(' ').append(w);
            } else {
                lines.add(cur.toString());
                cur = new StringBuilder(w);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    /**
     * Append a notice (possibly wrapped) into the output list.
     * If the notice contains multiple paragraphs (separated by "\n"), each paragraph
     * is wrapped independently.
     */
    // Simple word-wrap to prevent notices from cutting off on your display
    private static void appendWrappedNotice(List<String> out, String notice, int maxChars) {
        if (notice == null || notice.isEmpty()) return;

        StringBuilder currentLine = new StringBuilder();
        String[] words = notice.split("\\s+");

        for (String word : words) {
            // If adding the next word exceeds the max width, push the line and start a new one
            if (currentLine.length() + word.length() + 1 > maxChars) {
                out.add(currentLine.toString().trim());
                currentLine.setLength(0);
            }
            currentLine.append(word).append(" ");
        }

        // Add the final remaining piece of the notice
        if (currentLine.length() > 0) {
            out.add(currentLine.toString().trim());
        }
    }
}
