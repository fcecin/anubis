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

/**
 *
 * @author user
 */
public class Error {
    
    // Not error:
    
    public static final int OK                                  = 0;
    
    // Errors:
    
    public static final int FAILED                              = -1;
    public static final int EXPIRED                             = -2;
    public static final int NOT_FOUND                           = -3;
    public static final int ALREADY_EXISTS                      = -4;
    public static final int LIMIT_REACHED                       = -5;
    public static final int NOT_TRUSTED                         = -6;
    public static final int INSUFFICIENT_FUNDS                  = -7;
    public static final int INSUFFICIENT_AMOUNT                 = -8;
    public static final int INVALID_AMOUNT                      = -9;
    public static final int NOTHING_TO_DO                       = -10;
    public static final int FORBIDDEN                           = -11;
    public static final int TOO_MANY_REQUESTS                   = -12;
   
    public static final int INVALID_SOURCE                      = -50;
    public static final int INVALID_DESTINATION                 = -51;
    public static final int SOURCE_LIMIT_REACHED                = -52;
    public static final int DESTINATION_LIMIT_REACHED           = -53;
    
    public static final int EMPTY_NAME                          = -100;
    public static final int EMPTY_PROFILE                       = -101;
    
    public static final int NEVER_HAPPENS                       = -1000;
    public static final int EXCEPTION_NEVER_HAPPENS             = -1001;
    
    // Also not errors:
    
    public static final int OK_NO_SPONSOR                       = -99999;
}
