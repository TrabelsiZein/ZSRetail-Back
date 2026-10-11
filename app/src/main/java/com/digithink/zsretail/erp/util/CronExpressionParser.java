package com.digithink.zsretail.erp.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class to convert CRON expressions to human-readable descriptions.
 */
public class CronExpressionParser {

	/**
	 * Converts a CRON expression to a human-readable description.
	 * 
	 * @param cronExpression The CRON expression (6 fields: second minute hour day
	 *                       month weekday)
	 * @return Human-readable description
	 */
	public static String toHumanReadable(String cronExpression) {
		if (cronExpression == null || cronExpression.trim().isEmpty()) {
			return "Invalid CRON expression";
		}

		String[] parts = cronExpression.trim().split("\\s+");
		if (parts.length < 5) {
			return "Invalid CRON expression";
		}

		// Handle 5-field (minute hour day month weekday) or 6-field (second minute
		// hour day month weekday)
		int second = parts.length == 6 ? parseField(parts[0], 0, 59) : -1;
		int minuteIndex = parts.length == 6 ? 1 : 0;
		int hourIndex = parts.length == 6 ? 2 : 1;
		int dayIndex = parts.length == 6 ? 3 : 2;
		int monthIndex = parts.length == 6 ? 4 : 3;
		int weekdayIndex = parts.length == 6 ? 5 : 4;

		String minute = parts[minuteIndex];
		String hour = parts[hourIndex];
		String day = parts[dayIndex];
		String month = parts[monthIndex];
		String weekday = parts[weekdayIndex];

		List<String> descriptions = new ArrayList<>();

		// Every second
		if (second == 0 && "*".equals(minute) && "*".equals(hour) && "*".equals(day) && "*".equals(month)
				&& "*".equals(weekday)) {
			return "Every second";
		}

		// Every minute
		if ("*".equals(minute) && "*".equals(hour) && "*".equals(day) && "*".equals(month) && "*".equals(weekday)) {
			if (parts.length == 6 && second != -1) {
				return "Every minute";
			}
			return "Every minute";
		}

		// Every N minutes
		if (minute.startsWith("*/") && "*".equals(hour) && "*".equals(day) && "*".equals(month)
				&& "*".equals(weekday)) {
			try {
				int interval = Integer.parseInt(minute.substring(2));
				if (interval == 1) {
					return "Every minute";
				}
				return "Every " + interval + " minutes";
			} catch (NumberFormatException e) {
				// Fall through
			}
		}

		// Every hour
		if ("0".equals(minute) && "*".equals(hour) && "*".equals(day) && "*".equals(month) && "*".equals(weekday)) {
			return "Every hour at minute 0";
		}

		// Every N hours
		if (hour.startsWith("*/") && "*".equals(day) && "*".equals(month) && "*".equals(weekday)) {
			try {
				int interval = Integer.parseInt(hour.substring(2));
				String minuteStr = "0".equals(minute) ? "" : " at minute " + minute;
				return "Every " + interval + " hours" + minuteStr;
			} catch (NumberFormatException e) {
				// Fall through
			}
		}

		// Daily at specific time
		if (!"*".equals(minute) && !"*".equals(hour) && "*".equals(day) && "*".equals(month) && "*".equals(weekday)) {
			int h = parseField(hour, 0, 23);
			int m = parseField(minute, 0, 59);
			if (h != -1 && m != -1) {
				return String.format("Daily at %02d:%02d", h, m);
			}
		}

		// Weekly on specific day
		if (!"*".equals(weekday) && "*".equals(day) && "*".equals(month)) {
			String dayName = getWeekdayName(weekday);
			if (dayName != null) {
				int h = parseField(hour, 0, 23);
				int m = parseField(minute, 0, 59);
				if (h != -1 && m != -1) {
					return String.format("Every %s at %02d:%02d", dayName, h, m);
				}
				return "Every " + dayName;
			}
		}

		// Monthly on specific day
		if (!"*".equals(day) && "*".equals(month) && "*".equals(weekday)) {
			int dayNum = parseField(day, 1, 31);
			if (dayNum != -1) {
				int h = parseField(hour, 0, 23);
				int m = parseField(minute, 0, 59);
				if (h != -1 && m != -1) {
					return String.format("Monthly on day %d at %02d:%02d", dayNum, h, m);
				}
				return "Monthly on day " + dayNum;
			}
		}

		// Complex expression - return simplified version
		return "Custom schedule (" + cronExpression + ")";
	}

	private static int parseField(String field, int min, int max) {
		if (field == null || "*".equals(field)) {
			return -1;
		}
		try {
			int value = Integer.parseInt(field);
			if (value >= min && value <= max) {
				return value;
			}
		} catch (NumberFormatException e) {
			// Ignore
		}
		return -1;
	}

	private static String getWeekdayName(String weekday) {
		try {
			int day = Integer.parseInt(weekday);
			if (day == 0 || day == 7) {
				return "Sunday";
			} else if (day == 1) {
				return "Monday";
			} else if (day == 2) {
				return "Tuesday";
			} else if (day == 3) {
				return "Wednesday";
			} else if (day == 4) {
				return "Thursday";
			} else if (day == 5) {
				return "Friday";
			} else if (day == 6) {
				return "Saturday";
			}
		} catch (NumberFormatException e) {
			// Ignore
		}
		return null;
	}
}

