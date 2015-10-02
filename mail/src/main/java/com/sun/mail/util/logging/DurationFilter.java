/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2015 Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2015 Jason Mehrens. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.sun.mail.util.logging;

import static com.sun.mail.util.logging.LogManagerProperties.fromLogManager;
import java.util.logging.*;

/**
 * A filter used to limit log records based on a maximum generation rate.
 *
 * The duration specified is used to compute the record rate and the amount of
 * time the filter will reject records once the rate has been exceeded. Once the
 * rate is exceeded records are not allowed until the duration has elapsed.
 *
 * <p>
 * By default each {@code DurationFilter} is initialized using the following
 * LogManager configuration properties where {@code <filter-name>} refers to the
 * fully qualified class name of the handler. If properties are not defined, or
 * contain invalid values, then the specified default values are used.
 *
 * <ul>
 * <li>{@literal <filter-name>}.records the max number of records per duration.
 * A numeric long integer or a multiplication expression can be used as the
 * value. (defaults to {@code 1000})
 *
 * <li>{@literal <filter-name>}.duration the number of milliseconds to suppress
 * log records from being published. This is also used as duration to determine
 * the log record rate. A numeric long integer or a multiplication expression
 * can be used as the value. (defaults to {@code 15L * 60L * 1000L})
 * </ul>
 *
 * <p>
 * For example, the settings to limit {@code MailHandler} with a default
 * capacity to only send a maximum of two email messages every six minutes would
 * be as follows:
 * <pre>
 * {@code
 *  com.sun.mail.util.logging.MailHandler.filter = com.sun.mail.util.logging.DurationFilter
 *  com.sun.mail.util.logging.MailHandler.capacity = 1000
 *  com.sun.mail.util.logging.DurationFilter.records = 2L * 1000L
 *  com.sun.mail.util.logging.DurationFilter.duration = 6L * 60L * 1000L
 * }
 * </pre>
 *
 *
 * @author Jason Mehrens
 * @since JavaMail 1.5.5
 */
public class DurationFilter implements Filter {

    /**
     * The number of expected records per duration.
     */
    private final long records;
    /**
     * The duration in milliseconds used to determine the rate. The duration is
     * also used as the amount of time that the filter will not allow records
     * when saturated.
     */
    private final long duration;
    /**
     * The number of records seen for the current duration. This value negative
     * if saturated. Zero is considered saturated but is reserved for recording
     * the first duration.
     */
    private long count;
    /**
     * The most recent record time seen for the current duration.
     */
    private long peek;
    /**
     * The start time for the current duration.
     */
    private long start;

    /**
     * Creates the filter using the default properties.
     */
    public DurationFilter() {
        this.records = checkRecords(initLong(".records"));
        this.duration = checkDuration(initLong(".duration"));
    }

    /**
     * Creates the filter using the given properties. Default values are used if
     * any of the given values are outside the allowed range.
     *
     * @param records the number of records per duration.
     * @param duration the number of milliseconds to suppress log records from
     * being published.
     */
    public DurationFilter(final long records, final long duration) {
        this.records = checkRecords(records);
        this.duration = checkDuration(duration);
    }

    /**
     * Determines if this filter is equal to another filter.
     *
     * @param obj the given object.
     * @return true if equal otherwise false.
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }

        if (getClass() != obj.getClass()) {
            return false;
        }

        final DurationFilter other = (DurationFilter) obj;
        if (this.records != other.records) {
            return false;
        }

        if (this.duration != other.duration) {
            return false;
        }

        final long c;
        final long p;
        final long s;
        synchronized (other) {
            c = other.count;
            p = other.peek;
            s = other.start;
        }

        synchronized (this) {
            if (c != this.count || p != this.peek || s != this.start) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a hash code value for this filter.
     *
     * @return hash code for this filter.
     */
    @Override
    public int hashCode() {
        int hash = 3;
        hash = 89 * hash + (int) (this.records ^ (this.records >>> 32));
        hash = 89 * hash + (int) (this.duration ^ (this.duration >>> 32));
        return hash;
    }

    /**
     * Check if the given log record should be published. This method will
     * modify the internal state of this filter.
     *
     * @param record the log record to check.
     * @return true if allowed; false otherwise.
     * @throws NullPointerException if given record is null.
     */
    public boolean isLoggable(final LogRecord record) {
        return accept(record.getMillis());
    }

    /**
     * Determines if this filter will accept log records for this instant in
     * time. The result is a best-effort estimate and should be considered out
     * of date as soon as it is produced. This method is designed for use in
     * monitoring the state of this filter.
     *
     * @return true if the filter is not saturated; false otherwise.
     */
    public boolean isLoggable() {
        final long c;
        final long s;
        synchronized (this) {
            c = count;
            s = start;
        }

        final long millis = System.currentTimeMillis();
        if (c > 0L) { //If not saturated.
            if (c != records || (millis - s) >= duration) {
                return true;
            }
        } else {
            if ((millis - s) >= 0L || c == 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a string representation of this filter.
     *
     * @return a string representation of this filter.
     */
    @Override
    public String toString() {
        return getClass().getName() + "{records=" + records
                + ", duration=" + duration
                + ", loggable=" + isLoggable() + '}';
    }

    /**
     * Creates a copy of this filter that retains the filter settings but does
     * not include the current filter state. The newly create clone acts as if
     * it has never seen any records.
     *
     * @return a copy of this filter.
     * @throws CloneNotSupportedException if this filter is not allowed to be
     * cloned.
     */
    @Override
    protected DurationFilter clone() throws CloneNotSupportedException {
        final DurationFilter clone = (DurationFilter) super.clone();
        clone.count = 0L; //Reset the filter state.
        clone.peek = 0L;
        clone.start = 0L;
        return clone;
    }

    /**
     * Determines if the record is loggable by time.
     *
     * @param millis the log record milliseconds.
     * @return true if accepted false otherwise.
     */
    private synchronized boolean accept(final long millis) {
        boolean allow;
        if (count > 0L) { //If not saturated.
            if ((millis - peek) > 0L) {
                peek = millis; //Record the new peek.
            }

            //Under the rate if the count has not been reached.
            if (count != records) {
                ++count;
                allow = true;
            } else {
                if ((peek - start) >= duration) {
                    count = 1L;  //Start a new duration.
                    start = peek;
                    allow = true;
                } else {
                    count = -1L; //Saturate for the duration.
                    start = peek + duration;
                    allow = false;
                }
            }
        } else {
            //If the saturation period has expired or this is the first record
            //then start a new duration and allow records.
            if ((millis - start) >= 0L || count == 0L) {
                count = 1L;
                start = millis;
                peek = millis;
                allow = true;
            } else {
                allow = false; //Remain in a saturated state.
            }
        }
        return allow;
    }

    /**
     * Reads a long value or multiplication expression from the LogManager. If
     * the value can not be parsed or is not defined then Long.MIN_VALUE is
     * returned.
     *
     * @param suffix a dot character followed by the key name.
     * @return a long value or Long.MIN_VALUE if unable to parse or undefined.
     * @throws NullPointerException if suffix is null.
     */
    private long initLong(final String suffix) {
        long result;
        final String p = getClass().getName();
        String value = fromLogManager(p.concat(suffix));
        if (value != null && value.length() != 0) {
            try {
                result = 1L;
                for (String s : tokenizeLongs(value)) {
                    if (s.endsWith("L") || s.endsWith("l")) {
                        s = s.substring(0, s.length() - 1);
                    }
                    result = multiplyExact(result, Long.parseLong(s));
                }
            } catch (final RuntimeException ignore) {
                result = Long.MIN_VALUE;
            }
        } else {
            result = Long.MIN_VALUE;
        }
        return result;
    }

    /**
     * Parse any long value or multiplication expressions into tokens.
     *
     * @param value the expression or value.
     * @return an array of long tokens, never empty.
     * @throws NullPointerException if the given value is null.
     * @throws NumberFormatException if the expression is invalid.
     */
    private static String[] tokenizeLongs(String value) {
        value = value.trim();
        String[] e;
        final int i = value.indexOf('*');
        if (i > -1 && (e = value.split(
                "[\\s]*[\\x2A]{1}[\\s]*")).length != 0) {
            if (i == 0 || value.charAt(value.length() - 1) == '*') {
                throw new NumberFormatException(value);
            }

            if (e.length == 1) {
                throw new NumberFormatException(e[0]);
            }
        } else {
            e = new String[]{value};
        }
        return e;
    }

    /**
     * Multiply and check for overflow. This can be replaced with
     * {@code java.lang.Math.multiplyExact} when JavaMail requires JDK 8.
     *
     * @param x the first value.
     * @param y the second value.
     * @return x times y.
     * @throws ArithmeticException if overflow is detected.
     */
    private static long multiplyExact(final long x, final long y) {
        long r = x * y;
        if (((Math.abs(x) | Math.abs(y)) >>> 31L != 0L)) {
            if (((y != 0L) && (r / y != x))
                    || (x == Long.MIN_VALUE && y == -1L)) {
                throw new ArithmeticException();
            }
        }
        return r;
    }

    /**
     * Converts record count to a valid record count. If the value is out of
     * bounds then the default record count is used.
     *
     * @param records the record count.
     * @return a valid number of record count.
     */
    private static long checkRecords(final long records) {
        return records > 0L ? records : 1000L;
    }

    /**
     * Converts the duration to a valid duration. If the value is out of bounds
     * then the default duration is used.
     *
     * @param duration the duration to check.
     * @return a valid duration.
     */
    private static long checkDuration(final long duration) {
        return duration > 0L ? duration : 15L * 60L * 1000L;
    }
}
