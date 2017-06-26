/*
 * Copyright (C) 2017 user
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package anubis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;

/**
 * Static methods to operate on an int that represents UNIX Epoch time 
 *   but in minutes instead of seconds.
 */
public class Timestamp {
    
    public static long toEpoch(int timestamp) {
        return timestamp * 60;
    }
    
    public static int fromEpoch(long epoch) {
        return (int)(epoch / 60);
    }
    
    public static int fromDate(Date date) {
        return (int)(date.getTime() / 60000);
    }
    
    public static int now() {
        return (int)(Instant.now().getEpochSecond() / 60);
    }
    
    public static LocalDate toUTCLocalDate(int timestamp) {
        return Instant.ofEpochSecond(Timestamp.toEpoch(timestamp)).atOffset(ZoneOffset.UTC).toLocalDate();
    }

    public static LocalDateTime toUTCLocalDateTime(int timestamp) {
        return Instant.ofEpochSecond(Timestamp.toEpoch(timestamp)).atOffset(ZoneOffset.UTC).toLocalDateTime();
    }
}
