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

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Static methods to operate on an int that represents UNIX Epoch time 
 *   but in days instead of seconds.
 */
public class EpochDay {
    public static int now() {
        return (int)ChronoUnit.DAYS.between(LocalDate.ofEpochDay(0), LocalDate.now());
    }
}
