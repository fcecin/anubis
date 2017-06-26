/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

import anubis.BurnReceipt;
import anubis.LogEntry;
import anubis.Main;
import anubis.PrivateUserAccount;
import anubis.ServerInterface;
import anubis.ServerStats;
import anubis.SessionInfo;
import anubis.StoredPassword;
import anubis.Timestamp;
import anubis.UserAccount;
import anubis.UserPrivatePage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.ConnectException;
import java.rmi.NoSuchObjectException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.DatatypeConverter;

/**
 * Home page (default HTTP request).
 * 
 * This servlet should capture and handle everything sent to the domain.
 * (that is configured in the "web.xml")
 */
public class Home extends HttpServlet {
    
    static final String COOKIE_NAME_SESSION_ID = "sessionId";

    static final int MIN_PASS_LEN = 1;
    
    // ======================================================================
    // Configuration file (~/.anubis/client.properties)
    // ======================================================================
    
    Properties configProperties;
    
    synchronized void reloadConfigFile() {
        Path configPath = Paths.get(System.getProperty("user.home"), ".anubis", "client.properties");
        String configFile = configPath.toString();
        
        configProperties = new Properties();
        try {
            InputStream stream = new FileInputStream(configFile);
            configProperties.load(stream);
        } catch (IOException e) {
            getServletContext().log("WARNING: Cannot load Anubis client config file " + configFile, e);
            configProperties = null;
        }
    }
    
    synchronized Properties reloadConfigFileAndCloneProperties() {
        reloadConfigFile();
        Properties clone = new Properties();
        clone.putAll(configProperties);
        return clone;
    }

    synchronized String getConfig(String configName) {
        if (configProperties == null) {
            getServletContext().log("WARNING: Property '"+configName+"': No Anubis client config file loaded. Value set to null.");
            return null;
        }
        return configProperties.getProperty(configName);
    }

    synchronized String getConfig(String configName, String defaultValue) {
        if (configProperties == null)
            return defaultValue;
        return configProperties.getProperty(configName, defaultValue);
    }
    
    // ======================================================================
    // This singleton (per web server) servlet is a client to the Anubis RMI 
    //   server. Every thread used by the servlet container (i.e. the web
    //   server hosting the servlet) will allocate its own RMI client stub 
    //   instance, which I hope means every active worker thread of the servlet
    //   container will be able to have an independent TCP connection to make 
    //   RMI calls to Anubis.
    // ======================================================================
    
    class RMIClient {
        Registry registry;
        ServerInterface api;
        long lastRequestTime;
        RMIClient() throws Exception {
            reloadConfigFile(); // read RMI server/registry hostname/IP from a config file
            String rmiServerAddress = getConfig("rmi_server_address");
            if (rmiServerAddress == null || rmiServerAddress.length() == 0 
                    || rmiServerAddress.equals("localhost"))
                registry = LocateRegistry.getRegistry(anubis.Main.RMI_REGISTRY_PORT);
            else
                registry = LocateRegistry.getRegistry(rmiServerAddress, 
                        anubis.Main.RMI_REGISTRY_PORT);
            api = (ServerInterface) registry.lookup(anubis.Main.RMI_SERVER_NAME);
        }
        ServerInterface getAPI() { 
            return api; 
        }
        synchronized void touch() {
            lastRequestTime = Instant.now().getEpochSecond();
        }
        synchronized boolean isExpired() { 
            // One hour
            return (Instant.now().getEpochSecond() > lastRequestTime + 3600);
        }
    }
    
    final HashMap<Long, RMIClient> clients;
    
    RMIClient getClient() throws Exception {
        long threadId = Thread.currentThread().getId();
        RMIClient client;
        synchronized(clients) {
            client = clients.get(threadId);
            if (client == null) {
                client = new RMIClient();
                clients.put(threadId, client);
            }
            client.touch(); // this belongs here in the sync() really,
                            //   because of the reaper thread.
                            // it's safe to move it outside, but it's less nice.
        }
        return client;
    }
    
    void deleteAPI() {
        long threadId = Thread.currentThread().getId();
        synchronized(clients) {
            clients.remove(threadId);
        }
    }
    
    ServerInterface getAPI() throws Exception {
        return getClient().getAPI();
    }
    
    class ClientReaperThread extends Thread {
        @Override
        public void run() {
            while (true) {
                try {
                    // Half hour
                    Thread.sleep(1800 * 1000);
                } catch (InterruptedException e) {
                }
                
                synchronized (clients) {
                    Iterator it = clients.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry pair = (Map.Entry)it.next();
                        RMIClient client = (RMIClient)pair.getValue();
                        if (client != null)
                            if (client.isExpired())
                                it.remove();
                    }
                }
            }
        }
    }
    
    Thread clientReaperThread;
    
    // ======================================================================
    // All of the server stats support.
    // ======================================================================
    
    long servletCreationTime;
    long totalWebHits;
    
    Long lastStatsRefreshTime = 0L;
    ServerStats stats;

    // ======================================================================
    // Constructor, called when the web server boots up and instantiates 
    //   the servlet.
    // ======================================================================
    
    public Home() {
        servletCreationTime = Instant.now().getEpochSecond();
        clients = new HashMap();
        clientReaperThread = new Thread();
        clientReaperThread.setDaemon(true);
        clientReaperThread.start();
    }
    
    // ======================================================================
    // Helpers for the request handlers.
    // ======================================================================
    
    // Send e-mail
    void sendMail(String userName, String emailAddress, String subject, String body) throws Exception {
        Properties props = reloadConfigFileAndCloneProperties();
        String domainName = props.getProperty("domain_name", "democratic.money");
        Session session = Session.getInstance(props, null);
        Message msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress("no-reply@" + domainName, domainName));
        msg.addRecipient(Message.RecipientType.TO, new InternetAddress(emailAddress, userName));
        msg.setSubject(subject);
        msg.setText(body);
        Transport.send(msg);
    }
    
    // Minutes from epoch to UTC English/Gregorian date string.
    // TODO: - omit year if it's this year
    //       - generate better string (e.g. use 3-letter month abbreviation)
    String timeStringFromTimestamp(int timestamp) {
        LocalDateTime timePoint = 
                LocalDateTime.ofEpochSecond(
                        Timestamp.toEpoch(timestamp), 
                        0,
                        ZoneOffset.UTC
                        //ZoneId.systemDefault()
                        //        .getRules()
                        //        .getOffset(
                        //                Instant.ofEpochSecond(
                        //                        Timestamp.fromEpoch(timestamp)
                        //                                    )
                        //        )
                );
            return timePoint.toString();
    }
    
    String getTransactionDescription(short code, int userId) {
        switch (code) {
            case LogEntry.UBI:
                return "Daily democratic income credit (money creation)";
            case LogEntry.DEMURRAGE:
                return "Daily demurrage charge (money destruction)";
            case LogEntry.ACCOUNT_MAINTENANCE_FEE:
                return "Daily account maintenance fee";
            case LogEntry.INVITE_CREATE_FUNDS_LOCKED:
                return "Funds locked due to invitation code creation";
            case LogEntry.INVITE_CANCEL_FUNDS_UNLOCKED:
                return "Funds unlocked due to invitation code cancellation";
            case LogEntry.ACCOUNT_AUTHENTICATION_FEE:
                return "Account verification setup fee";
            case LogEntry.ACCOUNT_AUTHENTICATION_REWARD:
                return "Account verification participation reward";
            case LogEntry.ACCOUNT_AUTHENTICATION_APPROVED:
                return "Account verified; setup fee refund";
            case LogEntry.ACCOUNT_AUTHENTICATION_REJECTED:
                return "Account failed verification; setup fee refund";
            case LogEntry.INACTIVE_ACCOUNT:
                return "Daily account inactivity charge (money destruction)";
            case LogEntry.ACCOUNT_AUTH_CHALLENGE_FEE:
                return "Account #" + userId + " challenge setup fee";
            case LogEntry.ACCOUNT_AUTH_CHALLENGE_APPROVED:
                return "Account #" + userId + " verification upheld; setup fee refund";
            case LogEntry.ACCOUNT_AUTH_CHALLENGE_REJECTED:
                return "Account #" + userId + " verification reverted; setup fee refund";
            case LogEntry.BURN_MONEY:
                return "Burn money";
            case LogEntry.UNBURN_MONEY:
                return "Unburn money (burn money failed)";
                
            case LogEntry.SEND_MONEY:
                return "Sent money to <a href=\"/user?id=" + userId + "\">#" + userId + "</a>";
            case LogEntry.RECEIVE_MONEY:
                return "Received money from <a href=\"/user?id=" + userId + "\">#" + userId + "</a>";
            case LogEntry.INVITE_ACCEPT_FUNDS_UNLOCKED:
                return "Sent money to invited user <a href=\"/user?id=" + userId + "\">#" + userId + "</a>";
            case LogEntry.INVITE_ACCEPT_RECEIVE_MONEY:
                if (userId == anubis.Error.OK_NO_SPONSOR)
                    return "Arbitrary money creation by server operator";
                else
                    return "Received money by accepting invitation from user <a href=\"/user?id=" + userId + "\">#" + userId + "</a>";
                
            case LogEntry.TXFEE_EDIT_INFO:
                return "Transaction fee (edit settings)";
            case LogEntry.TXFEE_VALIDATION_INITIATION:
                return "Transaction fee (non-reciprocal validation set)";
            case LogEntry.TXFEE_CREATE_INVITE:
                return "Transaction fee (created invitation code)";
            case LogEntry.TXFEE_SEND_MONEY:
                return "Transaction fee (sent money)";
            case LogEntry.TXFEE_BURN_MONEY:
                return "Transaction fee (burn money)";
            case LogEntry.REFUND_TXFEE_BURN_MONEY:
                return "Refund of burn money transaction fee.";
                
            default:
                if (userId >= 0)
                    return "Unknown transaction code: " + code + " + user ID: #" + userId;
                else
                    return "Unknown transaction code: " + code;
        }
    }
    
    String removeTrailingSlashes(String uri) {
        while (uri.endsWith("/")) // remove ALL trailing slashes
            uri = uri.substring(0, uri.length() - 1);
        return uri;
    }
    
    String getInviteURL(long invitationCode) {
        String webRoot = getConfig("web_root", "https://democratic.money");
        webRoot = removeTrailingSlashes(webRoot);
        return webRoot + "/invite/" + String.valueOf(invitationCode);
    }

    String getPasswordResetURL(long resetCode) {
        String webRoot = getConfig("web_root", "https://democratic.money");
        webRoot = removeTrailingSlashes(webRoot);
        return webRoot + "/resetPasswordForm/" + String.valueOf(resetCode);
    }
    
    long getCookieSessionId(HttpServletRequest request) {
        long sessionId = -1;
        Cookie[] cookies = request.getCookies();
        if (cookies != null)
            for (Cookie cookie : cookies)
                if (cookie.getName().equals(COOKIE_NAME_SESSION_ID)) {
                    try { 
                        sessionId = Long.parseLong(cookie.getValue());
                    } catch (NumberFormatException e) {
                        sessionId = -1;
                    }
                    break;
                }
        return sessionId;
    }
    
    void deleteCookieSessionId(HttpServletResponse response) {
        Cookie deleteCookie = new Cookie(COOKIE_NAME_SESSION_ID, "");
        deleteCookie.setMaxAge(0);
        response.addCookie(deleteCookie);
    }
    
    boolean validatePassword(String password, PrintWriter out) {
        if (password.length() < MIN_PASS_LEN) {
            out.println("<p>Your password must be at least " + MIN_PASS_LEN + " characters long.");
            out.println("<p>Click your browser's back button to try again.");
            return false;
        }
        return true;
    }
    
    byte[] parseComment(String commentStr, PrintWriter out) {
        if (commentStr.length() / 2 > Main.MAX_BURN_MONEY_COMMENT_BYTES) {
            out.println("<p>Error: the given comment has " + (commentStr.length() / 2) + " bytes, which exceeds the server's limit of " + Main.MAX_BURN_MONEY_COMMENT_BYTES + " bytes.");
            out.println("<p>Given comment:");
            printDataString(commentStr, 8, out);
            out.println("<p>Click your browser's back button to try again.");
            return null;
        }
        if (commentStr.length() % 2 == 1) {
            out.println("<p>Error: the given comment hex number has an odd number of digits.");
            out.println("<p>Given comment:");
            printDataString(commentStr, 8, out);
            out.println("<p>Please enter an even number of hex digits to fully specify the value of all comment bytes.");
            out.println("<p>Click your browser's back button to try again.");
            return null;
        }
        try {
            return DatatypeConverter.parseHexBinary(commentStr);
        } catch (IllegalArgumentException e) {
            out.println("<p>Error: the given comment is not a valid hexadecimal number and cannot be converted to binary data.");
            out.println("<p>Given comment:");
            printDataString(commentStr, 8, out);
            out.println("<p>The only characters allowed in the comment string are 0 to 9 and A to F. Each character produces 4 bits of the binary output.");
            out.println("<p>Click your browser's back button to try again.");
            return null;
        }
    }
    
    // ======================================================================
    // HTML pseudotemplates reused in multiple handlers.
    // ======================================================================
    
    void printInternalError(PrintWriter out) {
        out.println("<p>An internal server error occurred and your request cannot be processed.");
        out.println("<p><a href=\"/\">Return to the main page</a>.");
    }

    void printNoSession(PrintWriter out) {
        out.println("<p>You are not signed in.");
        out.println("<p><a href=\"/\">Return to the main page</a>.");
    }
    
    void printLoginError(PrintWriter out) {
        out.println("<p>Cannot sign in with the given credentials. Please try again.");
        out.println("<p><a href=\"/\">Return to the main page</a>.");
    }

    void printAuthenticationError(PrintWriter out) {
        out.println("<p>Cannot authenticate your request with the given credentials.");
        out.println("<p><a href=\"/\">Return to the main page</a>.");
    }

    void printInvalidNumberError(String name, String val, PrintWriter out) {
        out.println("<p>An internal server error occurred and your request cannot be processed.");
        out.println("<p>Error: \"" + val + "\" is not a valid numeric value for \"" + name + "\".");
        out.println("<p><a href=\"/\">Return to the main page</a>.");
    }

    void printDataString(String str, int padding, PrintWriter out) {
        out.println("<div style=\"background:#F0F7FC;padding:" + padding + "px;word-wrap:break-word\">");
        out.println("<tt><font size=+2>" + str + "</font></tt></div>");
    }
    
    void printByteArray(byte[] data, int padding, PrintWriter out) {
        printDataString(DatatypeConverter.printHexBinary(data), padding, out);
    }
    
    // Print the public part of an user account.
    // If partOfPrivatePage==true, augments the output with information and 
    //   controls (forms/buttons) for use in the private page handler.
    void printPublicPage(int userId, UserAccount pub, boolean partOfPrivatePage, PrintWriter out) throws Exception {
        
        // user name and id
        out.println("<h2>" + pub.name + " <font color=gray>(#"+ userId +")</font></h2>");
        
        // user profile
        out.println("<div style=\"background:#F0F7FC;padding:8px;word-wrap:break-word\">");
        for (String line : pub.profile) {
            // split by whitespace, print and try to linkify each separate "word"
            String[] words = line.split("\\s+");
            for (int i = 0; i < words.length; i++) {
                String word = words[i];
                if (i != 0)
                    out.print(" ");
                try {
                    new URL(word);
                    out.print("<a href=\"" + word + "\">" + word + "</a>");
                } catch (MalformedURLException e) {
                    out.print(word);
                }
            }
            out.println("<br>");
        }
        out.println("</div><p>");

        // a little table with some data items
        
        // This nested table layout saves vertical space but destroys the CSS we 
        //   use for the event log's scrolling div/table later on
        //out.println("<table border=1 cellpadding=5 cellspacing=0><tr><td>");
        
        out.println("<table border=1 cellpadding=5 cellspacing=0>");
        
        if (partOfPrivatePage) {
            out.println("<tr><td>Balance:</td><td>" + Main.formatMoneyTicker(pub.getUnlockedBalance()) + "</td></tr>");
            if (pub.minBalance > 0)
                out.println("<tr><td>Locked:</td><td>" + Main.formatMoneyTicker(pub.minBalance) + "</td></tr>");
        } else {
            out.println("<tr><td>Balance:</td><td>" + Main.formatMoneyTicker(pub.balance) + "</td></tr>");
        }
        String status;
        if (pub.isAnchor())
            status = "Trusted (anchor)";
        else if (pub.isAuthentic())
            status = "Trusted (verified)";
        else
            status = "Not Trusted";
        out.println("<tr><td>Status:</td><td>" + status);
        
        if (!partOfPrivatePage && pub.isAuthentic() && !pub.isAnchor()) {
            // Challenge an user's trusted status button
            out.println("&nbsp;<form action=\"/challengeTrustConfirmationDialog\" method=\"get\">");
            out.println("<input type=\"hidden\" name=\"uid\" value=\"" + userId + "\">");
            out.println("<input type=\"submit\" value=\"Challenge\"></form>");
        }
        
        out.println("</td></tr>");

        // nested table layout
        //out.println("</table>");
        //out.println("</td><td>");
        //out.println("<table border=1 cellpadding=5 cellspacing=0>");
        //Instant.ofEpochSecond(Timestamp.toEpoch()).atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate creationDate = Timestamp.toUTCLocalDate(pub.creationTimestamp);
        out.println("<tr><td>Member since:</td><td> " + creationDate.toString() + "</td></tr>");
        
        if (pub.lastVerificationTimestamp > 0 && !pub.isAnchor()) {
            //Instant.ofEpochSecond(Timestamp.toEpoch(pub.lastVerificationTimestamp)).atOffset(ZoneOffset.UTC).toLocalDate();
            LocalDate verifyDate = Timestamp.toUTCLocalDate(pub.lastVerificationTimestamp);
            String verStr;
            if (pub.isAuthentic())
                verStr = "granted";
            else
                verStr = "removed";
            out.println("<tr><td>Trust " + verStr + ":</td><td> " + verifyDate.toString() + "</td></tr>");
        }
        
        out.println("</table>");
        
        // nested table layout
        //out.println("</td></tr><table>");

        // social validation in/out links
        HashSet<Integer> linkUserIds = new HashSet();
        linkUserIds.addAll(pub.validationIn);
        linkUserIds.addAll(pub.validationOut);
        ServerInterface api = getAPI();
        HashMap<Integer, String> linkUserNames = api.getUserNames(linkUserIds);
        if (linkUserNames != null) {
            if (pub.validationOut.size() > 0) {
                out.println("<h3>Validation given to:</h3>");
                boolean first = true;
                for (int vout : pub.validationOut) {
                    String name = linkUserNames.get(vout);
                    if (name == null)
                        name = "null (#" + vout + ")";
                    if (!first)
                        out.print(", ");
                    else
                        first = false;
                    out.print("<a href=\"/user?id=" + vout +"\">" + name + " (#" + vout + ")</a>");
                }
            }
            if (pub.validationIn.size() > 0) {
                out.println("<h3>Validation received from:</h3>");
                boolean first = true;
                for (int vin : pub.validationIn) {
                    String name = linkUserNames.get(vin);
                    if (name == null)
                        name = "null (#" + vin + ")";
                    if (!first)
                        out.print(", ");
                    else
                        first = false;
                    out.print("<a href=\"/user?id=" + vin +"\">" + name + " (#" + vin + ")</a>");
                }
            }
        }
            
        // user's recent transactions log
        out.println("<h3>Recent transactions:</h3>");
        out.println("<div id=\"scrolltable\" style=\"height:200px;overflow:auto\">");
        //<div style=\"position: absolute; margin-top: -30px;\">
        out.println("<table width=100% cellpadding=0 cellspacing=0 border=1 bordercolorlight=#EEEEEE bordercolordark=#EEEEEE bordercolor=#EEEEEE>");
        out.println("<thead><tr bgcolor=#D7E0EE>");
        out.println("<th>Time (UTC)</th>");
        out.println("<th width=60%>Description</th>");
        out.println("<th align=right>Amount (" + Main.TICKER + ")</th>");
        out.println("</tr></thead>");
        out.println("<tbody>");
        for (int i = pub.log.size() - 1; i >= 0 ; --i) {
            LogEntry le = pub.log.get(i);
            String amtCol = "black";
            String timeStr = timeStringFromTimestamp(le.getTimestamp());
            String cellBgCol = "#E7FCF0";
            if ((pub.log.size() - i) % 2 == 0)
                cellBgCol = "#F8FCFB";
            out.println("<tr bgcolor=" + cellBgCol + ">");
            out.println("<td>" + timeStr + "</td>");
            out.println("<td>" + getTransactionDescription(le.getCode(), le.getUserId()) + "</td>");
            out.println("<td align=right><font color=" + amtCol + ">" + Main.formatMoneyDecimals(le.getAmount(), Main.COIN_DECIMALS) + "</font></td>");
            out.println("</tr>");
        }
        out.println("</tbody></table>");
        out.println("</div>");
    }

    // ======================================================================
    // These are the various "web pages" within the client (URI handlers)
    //
    // ONLY write to the "response" argument object in any one of these 
    //  handlers AFTER ALL calls to the ServerInterface have been made. 
    // That is because RMI calls can blow up and that may trigger a retry 
    //  of the handler within the same servlet request, and if so we don't
    //  have a way to "reset" the request object. So don't change it before
    //  we're past the RMI "api" calls.
    // ======================================================================
/*
    // TESTING SERVER TICK
    public void handleTick(PrintWriter out) throws Exception {
        ServerInterface api = getAPI();
        api.forceTick();
        out.println("<p>Server ticked.");
    }
*/    
    // Handle session expired
    public void handleSessionExpired(HttpServletResponse response, PrintWriter out) throws Exception {
        out.println("<p>Your session has expired. You will have to sign in again.");
        out.println("<p><a href=\"/\">Return to the main page</a>.");

        deleteCookieSessionId(response);
    }
    
    // Homepage
    public void handleHome(SessionInfo sessionInfo, HttpServletRequest request, 
            HttpServletResponse response, PrintWriter out) throws Exception 
    {

        ServerInterface api = getAPI();
        
        // ----- homepage with a session cookie -----
        
        if (sessionInfo != null) {
            
            UserPrivatePage page = api.getUserPrivatePage(sessionInfo.sessionId);
            if (page == null) { printInternalError(out); return; }
            UserAccount pub = page.account;
            //PrivateUserAccount pvt = page.privateAccount;

            // ----- print the validation alerts/options header if any -----
            
            boolean authOther = pub.authOtherUserId >= 0;
            boolean authSelf = pub.authSelfUserId >= 0;
            boolean untrusted = (!pub.isAuthentic()) && (!pub.isAnchor());
            
            if (authOther || authSelf || untrusted)
                out.println("<table border=1 cellpadding=8 bgcolor=#FFDD77><tr><td>");

            if (authOther) {
                
                out.println("<p>You have been invited to verify the profile of another user.");
                out.println("<p>Each existing person has a right to one verified profile which reflects their actual person and their social connections (that you can assess by navigating a profile's validation links to other profiles).");
                out.println("<p><a href=\"/user?id=" + pub.authOtherUserId + "\">Please take some time to carefully examine and judge the profile page of <b>user #" + pub.authOtherUserId + "</b> now by clicking here.</a>");
                out.println("<p>When you have completed your assessment, please cast your vote below:");

                // Yes/No buttons
                out.println("<table cellpadding=8><tr>");
                out.println("<td><form action=\"/voteTrust\" method=\"post\">");
                out.println("<input type=\"hidden\" name=\"v\" value=\"Y\">");
                out.println("<input type=\"submit\" value=\"YES, trust user\"></form></td>");
                out.println("<td><form action=\"/voteTrust\" method=\"post\">");
                out.println("<input type=\"hidden\" name=\"v\" value=\"N\">");
                out.println("<input type=\"submit\" value=\"NO, don't trust user\"></form></td>");
                out.println("</tr></table>");
                
            } else if (authSelf) {
                
                if (pub.authSelfUserId == sessionInfo.userId) {
                
                    out.println("<p>You are waiting for other randomly selected users to verify your profile. This process takes at most a week.");
                    out.println("<p>If your profile passes the verification process, you will begin receiving your Democratic Income. If it doesn't, you should be able to improve your profile and validation links, and try again.");
                    
                } else {
                    
                    out.println("<p>The Trusted status of your profile has been challenged by another user.");
                    out.println("<p>You are waiting for other randomly selected users to re-verify your profile. This process takes at most a week.");
                    out.println("<p>If your profile fails the re-verification process, you will be able to ask to re-verify it yourself again. If it passes re-verification, you won't have to do anything.");
                }
                
            } else if (untrusted) {
                
                out.println("<p>Your profile is currently not verified. To start a (new) verification process for your profile, you will need to pay a fee of <b>" + Main.formatMoneyTicker(Main.AUTH_TOTAL_FEE) + "</b>.");
                
                if (pub.getUnlockedBalance() < Main.AUTH_TOTAL_FEE) {
                    
                    out.println("<p>Unfortunately, you do not have enough funds to cover the verification fee at this time.");
                    
                } else {
                    
                    out.println("<p>If you want to pay the fee and start the verification process, click the button below:");
                    
                    out.println("<form action=\"/requestTrust\" method=\"post\">");
                    out.println("<input type=\"submit\" value=\"Request Trusted status\"></form>");
                    
                    out.println("<p>TIP: The verification process is done by a random selection of up to " + Main.AUTH_TOTAL_VOTES + " trusted users. " 
                            + "To increase the odds that your account will become verified, <b>first write a good, short profile</b> with links to your social media accounts and personal or professional websites, as that will allow other users to actually judge your account based on your other online presence. "
                            + "If possible, <b>add a textual or link reference on your other social media profiles that points back to your <i>democratic.money</i> account</b> so your verifiers can have additional proof that someone is not simply hijacking your other online presence. "
                            + "And finally, <b>and perhaps even more importantly,</b> if you know of friends in the <i>democratic.money</i> network that you are not linked to, <b>contact them and ask them to validate your account</a> (and validate them back!)");
                }
            }
            
            if (authOther || authSelf || untrusted)
                out.println("</td></tr></table>");
            
            // ----- print the user's private home page -----

            printPublicPage(page.userId, pub, true, out);
            
            return;
        }
        
        // ----- homepage without a session cookie -----

        // Use cached results to hit the server only once every 5 minutes.
        synchronized (lastStatsRefreshTime) {
            if (Instant.now().getEpochSecond() > lastStatsRefreshTime + 300) {
                stats = api.getServerStats();
                lastStatsRefreshTime = Instant.now().getEpochSecond();
            }
        }

        out.println("<img width=304 height=146 src=\"democratic.money.png\"></img>");
        out.println("<p>A <a href=\"https://medium.com/democratic-money/what-is-the-democratic-money-project-bf440a6515f6\">simulation</a> of a <a href=\"https://medium.com/@fcecin/democratic-money-manifesto-58ed8257fbac\">democratic income monetary system</a>.");
        out.println("<p><b><font color=red>This service is in active development and testing. Data can be reset at any time.</font></b>");

        out.println("<p><br>Sign in:");
        out.println("<table cellpadding=4><form action=\"/login\" method=\"post\">");
        out.println("<tr><td>User ID # or email address:</td><td><input type=\"text\" name=\"u\" required></td></tr>");
        out.println("<tr><td>Password:</td><td><input type=\"password\" name=\"pw\" required></td></tr>");
        out.println("<tr><td><input type=\"submit\" value=\"Sign in\"></td><td><a href=\"/getPasswordResetCodeForm\">Forgot your password?</a></td></tr></form></table>");

        out.println("<p><br>");
        out.println("<table width=100% border=0 cellpadding=0 cellspacing=0><tr>");
        out.println("<td valign=top>");
        out.println("Signing up to new accounts is <a href=\"https://medium.com/democratic-money/the-basic-income-identification-problem-b488920fe514\">invitation-only</a>.");
        out.println("<p>Follow the project's <a href=\"https://medium.com/democratic-money\">Medium publication</a> for updates.");
        out.println("<p>For feedback and support, you can join the <a href=\"https://groups.google.com/forum/#!forum/democratic-money\">mailing list</a>.");
        out.println("</td>");
        out.println("<td width=30>");
        out.println("</td>");
        out.println("<td width=340>");
        out.println("<font size=+1><b>Server stats:</b></font><tt>");
        
        out.println("<br><table witdh=100% border=0 cellpadding=0 cellspacing=0>");
        out.println("<tr><td>Total users</td><td width=20></td><td align=right>" + stats.userCount + " users</td></tr>");
        out.println("<tr><td>Trusted users</td><td width=20></td><td align=right>" + stats.totalTrusted + " users</td></tr>");
        out.println("<tr><td>Total money</td><td width=20></td><td align=right>" + Main.formatMoneyTicker(stats.totalMoney) + "</td></tr>");
        out.println("<tr><td>Server balance</td><td width=20></td><td align=right>" + Main.formatMoneyTicker(stats.serverBalance) + "</td></tr>");
        out.println("<tr><td>Core hits (24h)</td><td width=20></td><td align=right>" + stats.recentTx + " tx</td></tr>");
        out.println("<tr><td>Core hits (all)</td><td width=20></td><td align=right>" + stats.totalTx + " tx</td></tr>");
        out.println("<tr><td>Core RAM used</td><td width=20></td><td align=right>" + (stats.usedMemoryBytes / (1024*1024)) + " MB</td></tr>");
        out.println("<tr><td>Web hits (all)</td><td width=20></td><td align=right>" + totalWebHits + " reqs</td></tr>");
        out.println("<tr><td>Web uptime</td><td width=20></td><td align=right>" + (Instant.now().getEpochSecond() - servletCreationTime) + "s</td></tr>");
        out.println("<tr><td>Total days</td><td width=20></td><td align=right>" + stats.totalDays + " days</td></tr>");
        out.println("<tr><td>Current day</td><td width=20></td><td align=right>" + LocalDate.ofEpochDay(stats.epochDay) + "</td></tr>");
        out.println("</table></tt>");
        
        out.println("</td>");
        out.println("</tr></table>");
    }

    // View an user's public profile given the user Id
    public void handleUser(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out, int userId) throws Exception {
        
        ServerInterface api = getAPI();
        UserAccount pub = api.getUserPublicPage(userId);
        if (pub == null) {
            out.println("<p>User ID # not found: " + userId);
            out.println("<p><a href=\"/\">Go back</a>.");
            return;
        }
        
        if (sessionInfo != null) {
            
            out.println("<table border=1 cellpadding=8 bgcolor=#D7EE99><tr><td>");
            
            if (sessionInfo.userId == userId) {
                
                out.println("This is your public page. This is how other users see your profile.");
                out.println("<br><a href=\"/\">Go to your home page</a>.");
                
            } else {
                
                UserAccount self = api.getUserPublicPage(sessionInfo.userId);
                if (self == null) { printInternalError(out); return; }
                
                // ---- validation/invalidation status and options box ----
                
                boolean outbound = self.validationOut.contains(userId);
                boolean inbound = self.validationIn.contains(userId);
                
                if (outbound && inbound) {
                    out.println("You are validating and being validated by this person.");
                } else if (outbound) {
                    out.println("You are validating this person.");
                } else if (inbound) {
                    out.println("This person is validating you. If you know this person, you can validate them back.");
                } else {
                    out.println("If you know this person, you can validate them.");
                }
                
                out.println("<table cellpadding=8><tr>");
                if (! outbound) {
                    // Validate (back) if haven't
                    out.println("<td><form action=\"/addValidation/" + userId + "\" method=\"post\">");
                    out.println("<input type=\"submit\" value=\"Validate user\"></form></td>");
                } 
                if (outbound || inbound) {
                    // Remove any validation links...
                    out.println("<td><form action=\"/removeValidation/" + userId + "\" method=\"post\">");
                    out.println("<input type=\"submit\" value=\"Remove/cancel validation\"></form></td>");
                } 
                out.println("</tr></table>");
            }
            out.println("</td></tr></table>");
        }
        
        printPublicPage(userId, pub, false, out);
    }

    // GET request /user?id=nnnn
    public void handleUserGet(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out) throws Exception {
        String userIdStr = request.getParameter("id");
        if (userIdStr == null) { printInternalError(out); return; }
        int userId;
        try { userId = Integer.parseInt(userIdStr); } catch (NumberFormatException e) { printInvalidNumberError("userId", userIdStr, out); return; }
        handleUser(sessionInfo, request, out, userId);
    }
    
    class AuthHelper {
        public StoredPassword hashedPassword;
        public int userId;
    }    
    AuthHelper authenticationHelper(HttpServletRequest request) throws Exception {
        
        // Get form values
        String user = request.getParameter("u");
        user = user.trim();
        if (user.length() == 0) return null;
        String password = request.getParameter("pw");
        if (password.length() == 0) return null;
        
        ServerInterface api = getAPI();
        
        // check if user is trying to login with email or user ID
        int userId;
        try {
            userId = Integer.valueOf(user);  // login with userId
        } catch (NumberFormatException e) { 
            userId = -1;                     // login with email
        }
        
        // figure out the userId from the e-mail address if it wasn't
        //   given by the user.
        if (userId < 0)
            userId = api.getUserId(user);
        
        // login error (email not found)
        if (userId < 0) return null;
        
        // Get the salt used to hash an user's password. Already blows up
        //  if the userId doesn't exist.
        String salt = api.getPasswordSalt(userId);
        if (salt == null) return null;

        // Hash the supplied password here in the web server so the core server
        //   doesn't have to.
        AuthHelper ret = new AuthHelper();
        ret.hashedPassword = StoredPassword.hashPassword(password, salt);
        ret.userId = userId;
        return ret;        
    }

    // Handle login form filled and POSTed to this handler.
    public void handleLogin(HttpServletRequest request, HttpServletResponse response, PrintWriter out) throws Exception {
        
        // run common tasks (parse standard POST arguments and return hashed
        //  password and an user ID translated possibly from email)
        AuthHelper helper = authenticationHelper(request);
        if (helper == null) { printLoginError(out); return; }

        // Check if userId/pass combo works.
        ServerInterface api = getAPI();
        long sessionId = api.login(helper.userId, helper.hashedPassword);
        if (sessionId < 0) { printLoginError(out); return; }
        
        // Success. Go log in.
        out.println("<p>Success! You have logged in to your account!");
        out.println("<p>You can now <a href=\"/\">view your home page</a>.");

        // Set/overwrite the user's current session cookie here.
        Cookie userCookie = new Cookie(COOKIE_NAME_SESSION_ID, String.valueOf(sessionId));
        userCookie.setMaxAge(60*60*24*365); //Store cookie "forever;" it will expire at the server first.
        response.addCookie(userCookie);        
    }

    // Handle logout
    public void handleLogout(SessionInfo sessionInfo, HttpServletRequest request, HttpServletResponse response, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        // Log out.
        ServerInterface api = getAPI();
        api.logout(sessionInfo.sessionId);
                
        out.println("<p>You are logged out of your account!");
        out.println("<p><a href=\"/\">Return to the main page</a>.");

        deleteCookieSessionId(response);
    }
    
    public void handleGetPasswordResetCode(HttpServletRequest request, PrintWriter out) throws Exception {
        
        // Get form values
        String userIdStr = request.getParameter("u");
        if (userIdStr == null) { printInternalError(out); return; }
        int userId;
        try { userId = Integer.parseInt(userIdStr); } catch (NumberFormatException e) { printInvalidNumberError("userId", userIdStr, out); return; }

        String emailAddress = request.getParameter("e");
        if (emailAddress == null || emailAddress.length() == 0) { printInternalError(out); return; }

        ServerInterface api = getAPI();
        long code = api.getPasswordResetCode(userId, emailAddress);
        if (code < 0) {
            out.println("<p>Error: the user ID # and e-mail address you provided are not associated with any user account in the system (or the server encountered an internal error).");
            out.println("<p><a href=\"/\">Return to the main page</a>.");
            return;
        }
        
        // email the password reset code to the user
        String userName = api.getUserName(userId);
        if (userName == null)
            userName = "User ID #" + userId;
        sendMail(userName, emailAddress, 
                "Password reset", 
                "To set a new password for your account, visit the URL below:\n"
                + "\n"
                + getPasswordResetURL(code) + "\n"
                + "\n"
                + "All password reset links expire daily at around midnight UTC. If the link above has expired, you just have to request a new one.\n"
                + "\n"
                + "If you did not request a password reset, you can just ignore this email. "
                + "You may also want to log in and change your password recovery email associated with your account. "
                + "(And making sure your current password is sufficiently long and random is always a good idea.) "
        );
        
        out.println("<h2>Reset password (step 2 of 3)</h2>");
        out.println("An e-mail has been sent to <b>" + emailAddress + "</b> with a link to a temporary password reset page.");
        out.println("The e-mail should arrive in a few minutes. Click the link on the e-mail message to finish resetting your password.");
    }
    
    public void handleGetPasswordResetCodeForm(PrintWriter out) throws Exception {
                
        out.println("<h2>Reset password (step 1 of 3)</h2>");
        out.println("Enter the user ID # and e-mail address associated with your account to get a password reset link by e-mail.");
        out.println("<p><table border=0 cellpadding=8 cellspacing=0>");
        out.println("<form action=\"/getPasswordResetCode\" id=\"getPasswordResetCode\" method=\"post\">");
        out.println("<tr><td>User ID #:</td><td><input type=\"text\" name=\"u\" required></td></tr>");
        out.println("<tr><td>E-mail address:</td>");
        out.println("<td><input type=\"text\" name=\"e\" value=\"\" required></td></tr>");
        out.println("<tr><td><input type=\"submit\" value=\"Get password reset link\"></td></tr>");
        out.println("</form></table>");
    }
    
    public void handleResetPassword(HttpServletRequest request, PrintWriter out) throws Exception {
        
        // Get form values
        String resetCodeStr = request.getParameter("resetCode");
        if (resetCodeStr == null) { printInternalError(out); return; }
        long resetCode;
        try { resetCode = Long.parseLong(resetCodeStr); } catch (NumberFormatException e) { printInvalidNumberError("resetCode", resetCodeStr, out); return; }
        
        String newPassword = request.getParameter("npw");
        if (! validatePassword(newPassword, out)) 
            return;
            
        // Hash the supplied password here in the web server so the core server
        //   doesn't have to.
        StoredPassword hashedPassword = StoredPassword.hashPassword(newPassword);
        
        ServerInterface api = getAPI();
        int err = api.resetPassword(hashedPassword, resetCode);
        if (err != anubis.Error.OK) {
            out.println("<p>Error: the password reset code is invalid (or the server encountered an internal error).");
            out.println("<p><a href=\"/\">Return to the main page</a>.");
            return;
        }
        
        out.println("<p>You have successfully changed your password.");
        out.println("<p><a href=\"/\">Return to the main page</a> and sign in with your new password.");
    }
    
    public void handleResetPasswordForm(PrintWriter out, long resetCode) throws Exception {
        
        out.println("<h2>Reset password (step 3 of 3)</h2>");
        out.println("Enter your desired new password:");
        out.println("<p><table border=0 cellpadding=8 cellspacing=0>");
        out.println("<form action=\"/resetPassword\" id=\"resetPassword\" method=\"post\">");
        out.println("<input type=\"hidden\" name=\"resetCode\" value=\"" + resetCode + "\">");
        out.println("<tr><td>New password:</td>");
        out.println("<td><input type=\"text\" name=\"npw\" value=\"\" required></td></tr>");
        out.println("<tr><td><input type=\"submit\" value=\"Change password\"></td></tr>");
        out.println("</form></table>");
    }
    
    // Print the send money form if you're logged in
    public void handleSettings(SessionInfo sessionInfo, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        ServerInterface api = getAPI();
        UserPrivatePage page = api.getUserPrivatePage(sessionInfo.sessionId);
        if (page == null) { printInternalError(out); return; }
        
        out.println("<h2>Settings</h2>");
        
        out.println("<h3>Personal information</h3>");
        
        // generate an account creation form with a post to /acceptInvite
        out.println("<table border=0 cellpadding=8 cellspacing=0>");
        out.println("<form action=\"/editSettings\" id=\"editSettings\" method=\"post\">");
        out.println("<tr><td>Name:<br><font size=-1>(A socially meaningful one)</font></td><td><input type=\"text\" name=\"n\" value=\""+page.account.name+"\" required></td></tr>");
        out.println("<tr><td>E-mail address:<br><font size=-1>(Optional, for password resets)</font></td>");
        out.println("<td><input type=\"text\" name=\"e\" value=\""+page.privateAccount.emailAddress+"\"></td></tr>");
        out.println("<tr><td colspan=2>Profile:<br><font size=-1>(maximum of " + UserAccount.PROFILE_STRING_LENGTH_LIMIT + " characters and " + UserAccount.PROFILE_LINES_LIMIT + " lines)</font></td></tr>");
        out.println("<tr><td colspan=2><textarea id=\"editSettings\" name=\"pf\" rows=\"10\" cols=\"80\" required>");
        for (String item : page.account.profile) {
            out.println(item);
        }
        out.println("</textarea></td></tr>");
        out.println("<tr><td><input type=\"submit\" value=\"Apply changes\"></td></tr>");
        out.println("</form></table>");
        
        out.println("<hr size=1>");
        
        out.println("<h3>Password</h3>");

        out.println("<table border=0 cellpadding=8 cellspacing=0>");
        out.println("<form action=\"/changePassword\" method=\"post\">");
        out.println("<tr><td>New password:<br><font size=-1>(" + MIN_PASS_LEN + " characters minimum)</font></td><td><input type=\"password\" name=\"npw\"></td></tr>");
        out.println("<tr><td><input type=\"submit\" value=\"Change password\" required></form></td></tr>");
        out.println("</form></table>");

        out.println("<hr size=1>");
        
        out.println("<h3>Other</h3>");
        
        out.println("<td><form action=\"/deleteAccountConfirmationDialog\" method=\"get\">");
        out.println("<input type=\"submit\" value=\"Delete my account\"></form></td>");
    }

    // User editing name,email,profile
    public void handleEditSettings(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        // Get form values
        String name = request.getParameter("n");
        String emailAddress = request.getParameter("e");
        ArrayList<String> profile = 
                new ArrayList(
                        Arrays.asList(
                                request.getParameter("pf")
                                        .split("\\r\\n|\\n|\\r")
                        )
                );
        
        ServerInterface api = getAPI();
        int err = api.editPersonalInfo(sessionInfo.sessionId, name, emailAddress, profile);
        if (err != anubis.Error.OK) {
            out.println("<p>Cannot update your personal information.");
            out.println("<p>Error code: " + err);
            out.println("<p><a href=\"/\">Return to your profile page</a>.");
            return;
        }
        
        out.println("<p>Personal information updated.");
        out.println("<p><a href=\"/\">Return to your profile page</a>.");
    }
    
    // User changing their password
    public void handleChangePassword(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        // Get form values
        String newPassword = request.getParameter("npw");
        if (! validatePassword(newPassword, out)) 
            return;
            
        // Hash the supplied password here in the web server so the core server
        //   doesn't have to.
        StoredPassword hashedPassword = StoredPassword.hashPassword(newPassword);
        
        ServerInterface api = getAPI();
        int err = api.changePassword(sessionInfo.sessionId, hashedPassword);
        if (err != anubis.Error.OK) {
            out.println("<p>Cannot change your password.");
            out.println("<p>Error code: " + err);
            out.println("<p><a href=\"/\">Return to your profile page</a>.");
            return;
        }
        
        out.println("<p>Your password has been changed.");
        out.println("<p><a href=\"/\">Return to your profile page</a>.");
    }
    
    public void handleDeleteAccount(SessionInfo sessionInfo, HttpServletRequest request, HttpServletResponse response, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        // run common tasks (parse standard POST arguments and return hashed
        //  password and an user ID translated possibly from email)
        AuthHelper helper = authenticationHelper(request);
        if (helper == null) { printAuthenticationError(out); return; }
        
        // If the userId is NOT US, also bomb (entered someone ELSE's 
        //   credentials for some weird reason)
        if (helper.userId != sessionInfo.userId) { printAuthenticationError(out); return; }
        
        // Check if userId/pass combo works
        ServerInterface api = getAPI();
        if (!api.authenticate(helper.userId, helper.hashedPassword)) 
            { printAuthenticationError(out); return; }
        
        // Okay, you're gone.
        int err = api.deleteAccount(sessionInfo.sessionId);
        if (err == anubis.Error.OK) {
            out.println("<p>You have deleted your account.");
            out.println("<p><a href=\"/\">Return to the main page</a>.");
            deleteCookieSessionId(response);
        } else {
            out.println("<p>An error occurred while attempting to delete your account.");
            out.println("<p>Error code: " + err);
            out.println("<p><a href=\"/\">Return to your profile page</a>.");
        }
    }
    
    public void handleDeleteAccountConfirmationDialog(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }

        out.println("<h2>Are you absolutely sure you want to PERMANENTLY DELETE YOUR ACCOUNT?</h2>");
        out.println("<h2><font color=red>WARNING: This action cannot be reversed.</font></h2>");
        out.println("<p>If you delete your account and later you decide that you want it back, you will have to be invited to join again and create another account with a different ID #.");
        out.println("<p>If you REALLY WANT to delete your account, confirm by entering your sign-in credentials below:");
        out.println("<p><table cellpadding=4><form action=\"/deleteAccount\" method=\"post\">");
        out.println("<tr><td>User ID# or E-mail address:</td><td><input type=\"text\" name=\"u\" required></td></tr>");
        out.println("<tr><td>Password:</td><td><input type=\"password\" name=\"pw\" required></td></tr>");
        out.println("<tr><td><input type=\"submit\" value=\"YES, DELETE MY ACCOUNT\"></td></tr>");
        out.println("</form></table>");
    }
        
    // Handle sendMoney
    public void handleSendMoney(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        // Get form values
        String userIdStr = request.getParameter("uid");
        int toUserId;
        try { toUserId = Integer.parseInt(userIdStr); } catch (NumberFormatException e) { printInvalidNumberError("userId", userIdStr, out); return; }

        String amountStr = request.getParameter("amt");
        BigDecimal amountFP;
        try { amountFP = new BigDecimal(amountStr); } catch (NumberFormatException e) { printInvalidNumberError("amount", amountStr, out); return; }
        BigDecimal amountSatoshis = amountFP.scaleByPowerOfTen(Main.COIN_DECIMALS);
        Long amount = amountSatoshis.longValue();
        //int nonce = Integer.parse Int(request.getParameter("nonce"));
                
        ServerInterface api = getAPI();
        long amountSent = api.sendMoney(sessionInfo.sessionId, toUserId, amount, true);
        if (amountSent < 0) {
            out.println("<p>Attempt to send " + Main.formatMoneyTicker(amount) + " to User ID # " + toUserId + " failed.");
            out.println("<p>Error code: " + amountSent);
            out.println("<p><a href=\"/\">Return to your profile page</a>.");
            return;
        } 
        
        out.println("<p>Transferred " + Main.formatMoneyTicker(amountSent) + " to User ID # " + toUserId + ".");
        out.println("<p><a href=\"/\">Return to your profile page</a>.");
    }
    
    // Print the send money form if you're logged in
    public void handleSendMoneyForm(SessionInfo sessionInfo, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        // Generate a random nonce every time the form is generated so 
        //   submitting the same request twice won't work.
        // The nonce of the last posted operation is kept at the server to 
        //   prevent duplicate requests executing when the user reposts the 
        //   same form twice by accident.
        //int nonce = new Random().nextInt();
        
        out.println("<h2>Send money</h2>");
        
        out.println("Transfer money to another user.");
        
        out.println("<p><table border=0 cellpadding=8 cellspacing=0>");
        out.println("<form action=\"/sendMoneyConfirmationDialog\" method=\"post\">");
        //out.println("<input type=\"hidden\" name=\"nonce\" value=\"" + nonce + "\">");
        out.println("<tr><td>Recipient's User ID #:</td><td><input type=\"text\" name=\"uid\" required></td></tr>");
        out.println("<tr><td>Amount to send:</td><td><input type=\"text\" name=\"amt\" required></td></tr>");
        out.println("<tr><td>Transaction fee:</td><td>" + Main.formatMoneyTicker(Main.TRANSACTION_FEE) + "</td></tr>");
        out.println("<tr><td colspan=2><input type=\"submit\" value=\"Send money\"></td></tr>");
        out.println("</form></table>");
    }
    
    // Confirm money send
    public void handleSendMoneyConfirmationDialog(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        // Get form values
        String amountStr = request.getParameter("amt");
        BigDecimal amountFP;
        try { amountFP = new BigDecimal(amountStr); } catch (NumberFormatException e) { printInvalidNumberError("amount", amountStr, out); return; }
        BigDecimal amountSatoshis = amountFP.scaleByPowerOfTen(Main.COIN_DECIMALS);
        Long amount = amountSatoshis.longValue();
        if (amount <= 0) {
            out.println("<p>Invalid amount: " + amount);
            out.println("<p>Click your browser's back button to try again.");
            return;
        }
        String paramUid = request.getParameter("uid");
        int toUserId;
        try { toUserId = Integer.parseInt(paramUid); } catch (NumberFormatException e) { printInvalidNumberError("userId", paramUid, out); return; }
        //int nonce = Integer.parse Int(request.getParameter("nonce"));
        
        ServerInterface api = getAPI();
        HashSet<Integer> dummy = new HashSet();
        dummy.add(toUserId);
        HashMap<Integer, String> userNames = api.getUserNames(dummy);
        if (userNames == null) { printInternalError(out); return; }
        if (userNames.size() < 1) {
            out.println("<p>User ID # not found: " + toUserId);
            out.println("<p><a href=\"/sendMoneyForm\">Go back</a>.");
            return;
        }
        
        out.println("<p>Confirm money transfer:");
        out.println("<p><table border=0 cellpadding=8 cellspacing=0>");
        out.println("<tr><td>To:</td><td><a href=\"/user?id=" + paramUid + "\"><b>" + userNames.get(toUserId) + "</b> (#" + paramUid + ")</a></td></tr>");
        out.println("<tr><td>Amount:</td><td><b>" + amountStr + " " + Main.TICKER + "</b></td></tr><table>");
        
        out.println("<p><table border=0 cellpadding=8 cellspacing=0><tr>");
        out.println("<td><form action=\"/sendMoney\" method=\"post\">");
        //out.println("<input type=\"hidden\" name=\"nonce\" value=\"" + nonce + "\">");
        out.println("<input type=\"hidden\" name=\"uid\" value=\"" + paramUid + "\">");
        out.println("<input type=\"hidden\" name=\"amt\" value=\"" + amountStr + "\">");
        out.println("<input type=\"submit\" value=\"Send money\"></form></td>");
        out.println("<td><form action=\"/sendMoneyForm\" method=\"get\">");
        out.println("<input type=\"submit\" value=\"Cancel\"></form></td>");
        out.println("</tr></table>");
    }

    // Handle burnMoney
    public void handleBurnMoney(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        // Get form values
        String amountStr = request.getParameter("amt");
        BigDecimal amountFP;
        try { amountFP = new BigDecimal(amountStr); } catch (NumberFormatException e) { printInvalidNumberError("amount", amountStr, out); return; }
        BigDecimal amountSatoshis = amountFP.scaleByPowerOfTen(Main.COIN_DECIMALS);
        Long amount = amountSatoshis.longValue();
        if (amount <= 0) {
            out.println("<p>Invalid amount: " + amount);
            out.println("<p>Click your browser's back button to try again.");
            return;
        }
        String commentStr = request.getParameter("comment").trim();
        byte[] comment = parseComment(commentStr, out);
        if (comment == null)
            return;
        
        ServerInterface api = getAPI();
        byte[] receipt = api.burnMoney(sessionInfo.sessionId, amount, comment);
        if (receipt == null) {
            out.println("<p>Attempt to burn " + Main.formatMoneyTicker(amount) + " failed.");
            out.println("<p>Click your browser's back button to try again.");
            return;
        } 
        
        out.println("<p>Successfully burned " + Main.formatMoneyTicker(amount) + ".");
        out.println("<p>The following burn receipt is signed by the server's master key:");
        printByteArray(receipt, 16, out);
        out.println("<p>The receipt for the last burn operation is always saved on your account and can be viewed by accessing the burn money page again.");
        out.println("<p><a href=\"/\">Return to your profile page</a>.");
    }
    
    // Print the send money form if you're logged in
    public void handleBurnMoneyForm(SessionInfo sessionInfo, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }

        out.println("<h2>Burn money</h2>");
        
        ServerInterface api = getAPI();
        String puk = api.getPublicKey();
        if (puk == null) {
            out.println("The money burning option is not currently available as the server does not have a master key to sign proof-of-burn receipts.");
            out.println("<p><a href=\"/\">Return to your profile page</a>.");        
            return;            
        }
        
        UserPrivatePage page = api.getUserPrivatePage(sessionInfo.sessionId);
        if (page == null) { printInternalError(out); return; }

        out.println("A proof of money destruction can be consumed by external systems that trust this server's master key. "
                + "After filling out the form below and submitting it, the specified amount of money will be destroyed "
                + "and a receipt (proof) will be signed with the server's private key that matches the public key specified below.");
        
        out.println("<p><table border=0 cellpadding=8 cellspacing=0>");
        out.println("<form action=\"/burnMoneyConfirmationDialog\" method=\"post\">");
        out.println("<tr><td>Amount to destroy:</td><td><input type=\"text\" name=\"amt\" required></td></tr>");
        out.println("<tr><td>Comment (hexadecimal string):</td><td><input type=\"text\" name=\"comment\"></td></tr>");
        out.println("<tr><td>Transaction fee:</td><td>" + Main.formatMoneyTicker(Main.TRANSACTION_FEE) + "</td></tr>");
        out.println("<tr><td colspan=2><input type=\"submit\" value=\"Burn money\"></td></tr>");
        out.println("</form></table>");
        
        // Print last receipt if any
        if (page.privateAccount.burnReceipt != null) {
            out.println("<p><hr size=1>");
            out.println("<h3>Last burn receipt</h3>");
            out.println("This is a <a href=\"https://medium.com/@fcecin/the-money-burning-mechanism-in-the-democratic-money-server-5e9f3425eb40\">signed proof</a> of your last burn. <b>If you request a new burn, this information will be destroyed!</b> Save a copy of this receipt elsewhere before creating a new one.<p>");
            printByteArray(page.privateAccount.burnReceipt, 16, out);
        
            try {
                BurnReceipt br = new BurnReceipt(page.privateAccount.burnReceipt);
                out.println("<p>This is burn <b>UID# " + br.uid + "</b> of <b>" + Main.formatMoneyTicker(br.amount) + "</b> done on <b>" + Timestamp.toUTCLocalDateTime(br.timestamp) + " UTC</b>.");
            } catch (Exception ex) {
                ex.printStackTrace(out);
            }
        }

        // Print server's public key
        out.println("<p><hr size=1>");
        out.println("<h3>Public key</h3>");
        printDataString(puk, 16, out);
    }
    
    // Confirm money send
    public void handleBurnMoneyConfirmationDialog(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        // Get form values
        String amountStr = request.getParameter("amt");
        BigDecimal amountFP;
        try { amountFP = new BigDecimal(amountStr); } catch (NumberFormatException e) { printInvalidNumberError("amount", amountStr, out); return; }
        BigDecimal amountSatoshis = amountFP.scaleByPowerOfTen(Main.COIN_DECIMALS);
        Long amount = amountSatoshis.longValue();
        if (amount <= 0) {
            out.println("<p>Invalid amount: " + amount);
            out.println("<p>Click your browser's back button to try again.");
            return;
        }
        String commentStr = request.getParameter("comment").trim().toUpperCase();
        byte[] comment = parseComment(commentStr, out);
        if (comment == null)
            return;
        
        out.println("<p><b><font color=red>WARNING: You are about to destroy money.<br>Make sure you know what you're doing!</font></b>");
        out.println("<p>Confirm money burn:");
        out.println("<p><table border=0 cellpadding=8 cellspacing=0>");
        out.println("<tr><td>Amount:</td><td><b>" + amountStr + " " + Main.TICKER + "</b></td></tr>");
        out.println("<tr><td>Comment (" + (commentStr.length() / 2) + " bytes):</td></tr></table>");//<td><b>" + commentStr + "</b></td></tr></table>");
        printDataString(commentStr, 8, out);
        
        out.println("<p><table border=0 cellpadding=8 cellspacing=0><tr>");
        out.println("<td><form action=\"/burnMoney\" method=\"post\">");
        out.println("<input type=\"hidden\" name=\"amt\" value=\"" + amountStr + "\">");
        out.println("<input type=\"hidden\" name=\"comment\" value=\"" + commentStr + "\">");
        out.println("<input type=\"submit\" value=\"Burn money\"></form></td>");
        out.println("<td><form action=\"/burnMoneyForm\" method=\"get\">");
        out.println("<input type=\"submit\" value=\"Cancel\"></form></td>");
        out.println("</tr></table>");
    }

    // Find an user's public profile by given user ID
    public void handleFindUser(SessionInfo sessionInfo, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        out.println("<h2>Find user</h2>");
        out.println("Enter the ID number of an user's account below to view their public profile page.");
        
        out.println("<p><table border=0 cellpadding=8 cellspacing=0>");
        out.println("<form action=\"/user\" method=\"get\">");
        out.println("<tr><td>User ID#:</td><td><input type=\"text\" name=\"id\" required></td></tr>");
        out.println("<tr><td colspan=2><input type=\"submit\" value=\"Find user\"></td></tr>");
        out.println("</form></table>");
    }
    
    // Handle validating someone else (adding outbound link)
    public void handleAddValidation(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out, int linkUserId) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        ServerInterface api = getAPI();
        int err = api.addValidation(sessionInfo.sessionId, linkUserId);
        if (err == anubis.Error.OK) {
            out.println("<p>Added validation link to User ID # " + linkUserId + ".");
        } else {
            out.println("<p>Cannot add validation link to User ID # " + linkUserId + ".");
            out.println("<p>Error code: " + err);
        }
        out.println("<p><a href=\"/\">Return to your profile page</a>.");        
    }

    // Handle outbound validation link removed
    public void handleRemoveValidation(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out, int linkUserId, boolean inbound, boolean outbound) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        ServerInterface api = getAPI();
        int err = api.removeValidation(sessionInfo.sessionId, linkUserId, inbound, outbound);
        if (err == anubis.Error.OK) {
            out.println("<p>Removed validation link from/to User ID # " + linkUserId + ".");
        } else {
            out.println("<p>Cannot remove validation link from/to User ID # " + linkUserId + ".");
            out.println("<p>Error code: " + err);
        } 
        out.println("<p><a href=\"/\">Return to your profile page</a>.");
    }

    // Untrusted user wants to pay for their own profile authentication
    //    through the crowdsourced voting process.
    public void handleRequestTrust(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        ServerInterface api = getAPI();
        int err = api.requestTrust(sessionInfo.sessionId);
        if (err == anubis.Error.OK) {
            out.println("<p>Your request for Trusted status has been accepted.");
            out.println("<p>A random selection of users will vote on whether to authenticate your profile or not. The voting will take at most a week.");
        } else {
            out.println("<p>Cannot accept your request for Trusted status.");
            out.println("<p>Error code: " + err);
        }
        out.println("<p><a href=\"/\">Return to your profile page</a>.");
    }

    // Trusted and selected user wants to vote at an user authentication election.
    public void handleVoteTrust(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        boolean vote;
        String voteStr = request.getParameter("v");
        if (voteStr == null) { printInternalError(out); return; }
        if (voteStr.equalsIgnoreCase("Y")) {
            vote = true;
        } else if (voteStr.equalsIgnoreCase("N")) {
            vote = false;
        } else { printInternalError(out); return; }
        
        ServerInterface api = getAPI();
        int err = api.voteTrust(sessionInfo.sessionId, vote);
        if (err == anubis.Error.OK) {
            out.println("<p>Your vote has been registered. Thank you!");
        } else {
            out.println("<p>Error registering your vote.");
            out.println("<p>Error code: " + err);
        }
        out.println("<p><a href=\"/\">Return to your profile page</a>.");
    }
    
    public void handleChallengeTrust(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        String targetUserIdStr = request.getParameter("uid");
        if (targetUserIdStr == null) { printInternalError(out); return; }
        int targetUserId = Integer.valueOf(targetUserIdStr);
        
        ServerInterface api = getAPI();
        int err = api.challengeTrust(sessionInfo.sessionId, targetUserId);
        if (err == anubis.Error.OK) {
            out.println("<p>Your challenge has been accepted and <a href=\"/user?id=" + targetUserId + "\">User #" + targetUserId + "</a> is being re-verified.");
        } else {
            out.println("<p>Your challenge cannot be accepted due to an error.");
            out.println("<p>Error code: " + err);
        }
        out.println("<p><a href=\"/\">Return to your profile page</a>.");
    }
    
    public void handleChallengeTrustConfirmationDialog(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        String targetUserIdStr = request.getParameter("uid");
        if (targetUserIdStr == null) { printInternalError(out); return; }
        int targetUserId = Integer.valueOf(targetUserIdStr);
        
        out.println("<p>You are choosing to pay to challenge the Trusted (verified) status of another user's account. " 
                + "You can do this if you have reasons to believe that account not authentic, that is, "
                + "that it does not describe an existing person or that it is not linked to that person (a hijacked account or identity).");
        out.println("<p>Challenging the Trusted (verified) status of another user's account will cost you <b>" + Main.formatMoneyTicker(Main.AUTH_CHALLENGE_TOTAL_FEE) + "</b>.");
        out.println("<p><b>Are you SURE you want to pay the fee and challenge the Trusted (verified) status of <a href=\"/user?id=" + targetUserId + "\">User #" + targetUserId + "</a>?</b>");
        
        // Yes/No buttons
        out.println("<table cellpadding=8><tr>");
        out.println("<td><form action=\"/challengeTrust\" method=\"post\">");
        out.println("<input type=\"hidden\" name=\"uid\" value=\"" + targetUserIdStr + "\">");
        out.println("<input type=\"submit\" value=\"YES, re-verify them\"></form></td>");
        out.println("<td><form action=\"/\" method=\"get\">");
        out.println("<input type=\"hidden\">");
        out.println("<input type=\"submit\" value=\"NO, leave them alone\"></form></td>");
        out.println("</tr></table>");
    }
    
    // Profile tab to create/cancel invites
    public void handleManageInvites(SessionInfo sessionInfo, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        ServerInterface api = getAPI();
        UserPrivatePage page = api.getUserPrivatePage(sessionInfo.sessionId);
        if (page == null) { printInternalError(out); return; }
        UserAccount pub = page.account;
        PrivateUserAccount pvt = page.privateAccount;
        
        out.println("<h2>Invitations</h2>");
        
        out.println("<h3>Create</h3>");
        
        // IF pending invitations <20 AND enough funds AND trusted, 
        //   show new invite form/button.
        if (
                (pvt.pendingInvitationCodes.size() < Main.MAX_PENDING_INVITES)
                && (pub.getUnlockedBalance() >= (Main.MIN_INVITE_AMOUNT))
                && (pub.isAnchor() || pub.isAuthentic())
           )
        {
            // Invite new user button
            out.println("<p><form action=\"/newInvite\" method=\"post\">");
            out.println("<input type=\"submit\" value=\"Invite a friend to join\"></form>");
        } else {
            out.println("You cannot create a new invitation code at the moment.");
        }

        out.println("<p><hr size=1>");
        out.println("<h3>Manage</h3>");

        if (pvt.pendingInvitationCodes.size() > 0) {
            
            out.println("Pending invitation codes:");
                        
            out.println("<p><table border=0 cellpadding=5 cellspacing=0>");
            for (long ic : pvt.pendingInvitationCodes) {
                String inviteURL = getInviteURL(ic);
                out.println("<tr><td>");
                printDataString(inviteURL, 8, out);
                out.println("</td><td>");
                out.println("<form action=\"/cancelInvite/" + ic + "\" method=\"post\">");
                out.println("<input type=\"submit\" value=\"Cancel\"></form>");

                out.println("</td></tr>");
            }
            out.println("</table>");
        } else {
            out.println("You don't have any invitation codes at the moment.");
        }
    }

    // Handle creation of a new user invite
    public void handleNewInvite(SessionInfo sessionInfo, HttpServletRequest request, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        ServerInterface api = getAPI();
        long invitationCode = api.createInvite(sessionInfo.sessionId);
        if (invitationCode < 0) {
            out.println("<p>Error creating a new user invitation code.");
            out.println("<p>Error code: " + invitationCode);
        } else {
            out.println("<p>You have successfully created a new invitation code:");

            printDataString(getInviteURL(invitationCode), 8, out);
            //out.println("<p><table border=1 cellpadding=16 cellspacing=0><tr><td><font size=+1 color=blue>" + getInviteURL(invitationCode) + "</font></td></tr></table>");
            //out.println("<div style=\"background:#F0F7FC;padding:8px;word-wrap:break-word\">");
            //out.println("<tt><font size=+2>" + getInviteURL(invitationCode) + "</font></tt></div>");
            
            out.println("<p>Share the link above with a friend you know to sponsor and validate their new account.");
            out.println("<p>You will transfer <b>" + Main.formatMoneyTicker(Main.MIN_INVITE_AMOUNT) + "</b> to your friend "
                    + "when they successfully create their account using the invitation link above. "
                    + "That amount will remain locked in your account until your invitation code is either used or cancelled.");
        }
        out.println("<p><a href=\"/manageInvites\">Go back</a>.");
    }

    // Cancel an invitation code. (anyone can do this; it's like a session ID)
    public void handleCancelInvite(HttpServletRequest request, PrintWriter out, long invitationCode) throws Exception {
        ServerInterface api = getAPI();
        int err = api.cancelInvite(invitationCode);
        
        if (err == anubis.Error.OK) {
            out.println("<p>Invitation code cancelled.");
        } else {
            out.println("<p>Error cancelling new user invitation code.");
        }
        out.println("<p><a href=\"/manageInvites\">Go back</a>.");
    }
    
    // Handle invitation code just used to ask creation of a new account.
    // Verify the code is still valid (code is "touched" at the server-side); 
    // If so, show the create user form with a hard-coded invitation code in it.
    public void handleCheckInvite(PrintWriter out, long invitationCode) throws Exception {

        ServerInterface api = getAPI();
        int sponsorId = api.checkInvite(invitationCode);
        if ((sponsorId < 0) && (sponsorId != anubis.Error.OK_NO_SPONSOR)) {
            out.println("<p><br><br>");
            out.println("<p>Invalid or expired invitation code: " + invitationCode);
            out.println("<p><a href=\"/\">Return to the main page</a>.");
            return;
        }

        // Name of the person inviting us
        String sponsorName;
        if (sponsorId == anubis.Error.OK_NO_SPONSOR) {
            sponsorName = "--invite_anchor";
        } else {
            HashSet<Integer> dummy = new HashSet();
            dummy.add(sponsorId);
            HashMap<Integer, String> userNames = api.getUserNames(dummy);
            if ((userNames == null) || (userNames.size() != 1)) { printInternalError(out); return; }
            sponsorName = userNames.get(sponsorId);
        }
        
        // generate an account creation form with a post to /acceptInvite
        out.println("<h2>Welcome to democratic.money!</h2>");
        out.println("You have been invited by <b><a href=\"/user?id=" + sponsorId + "\">" + sponsorName + "</a></b> to join. Just fill out and submit the form below.");
        out.println("<p>This social network is a simulation of a democratic monetary system, and it is based " 
                + "on mutual identity verification of users by other users. The name and profile details "
                + "you provide below will be judged by other users as to whether they uniquely and reliably "
                + "identify a real person."
        );

        out.println("<table border=0 cellpadding=8 cellspacing=0>");
        out.println("<p><form action=\"/acceptInvite\" id=\"createNewAccount\" method=\"post\">");
        out.println("<input type=\"hidden\" name=\"ic\" value=\""+ invitationCode + "\">");
        out.println("<tr><td>Name:<br><font size=-1>(A socially meaningful one)</font></td><td><input type=\"text\" name=\"n\" required></td></tr>");
        out.println("<tr><td>E-mail address:<br><font size=-1>(Optional, for password resets)</font></td>");
        out.println("<td><input type=\"text\" name=\"e\"></td></tr>");
        out.println("<tr><td>Password:<br><font size=-1>(" + MIN_PASS_LEN + " characters minimum)</font></td><td><input type=\"password\" name=\"pw\" required></td></tr>");
        out.println("<tr><td colspan=2>Profile:<br><font size=-1>(maximum of " + UserAccount.PROFILE_STRING_LENGTH_LIMIT + " characters and " + UserAccount.PROFILE_LINES_LIMIT + " lines)</font></td></tr>");
        out.println("<tr><td colspan=2><textarea id=\"createNewAccount\" name=\"pf\" rows=\"10\" cols=\"80\" required>");
        out.println("</textarea></td></tr>");
        out.println("<tr><td><input type=\"submit\" value=\"Create your account\"></td></tr>");
        out.println("</form></table>");
    }
    
    // Handle create new user form filled and POSTed to this handler.
    public void handleAcceptInvite(HttpServletRequest request, PrintWriter out) throws Exception {
        
        // Get form values
        String password = request.getParameter("pw");
        if (! validatePassword(password, out)) 
            return;

        String name = request.getParameter("n");
        String emailAddress = request.getParameter("e");
        String invitationCodeStr = request.getParameter("ic");
        long invitationCode;
        try { invitationCode = Long.parseLong(invitationCodeStr); } catch (NumberFormatException e) { printInvalidNumberError("invitationCode", invitationCodeStr, out); return; }

        ArrayList<String> profile = 
                new ArrayList(
                        Arrays.asList(
                                request.getParameter("pf")
                                        .split("\\r\\n|\\n|\\r")
                        )
                );
        
        // Hash the supplied password here in the web server so the core server
        //   doesn't have to.
        StoredPassword hashedPassword = StoredPassword.hashPassword(password);
        
        ServerInterface api = getAPI();
        int newUserId = api.acceptInvite(invitationCode, emailAddress, hashedPassword, name, profile);
        
        if (newUserId < 0) {
            // Failed
            out.println("<p>Failed to create user account. ");
            out.println("<p>Error code: " + newUserId);
            out.println("<p>Press the back button on your browser to try again, or <a href=\"/\">return to the main page</a>.");
        } else {
            // Success. Go log in.
            out.println("<p>Success! You have created your account! (UserAccount ID: " + newUserId + ")");
            out.println("<p>You can now <a href=\"/\">log in to your account</a> with your e-mail address and password.");
        }
    }
    
    public void handleAdminShutdown(SessionInfo sessionInfo, PrintWriter out) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        ServerInterface api = getAPI();
        int err = api.shutdown(sessionInfo.sessionId);
        if (err == anubis.Error.OK)
            out.println("<p>OK.");
        else
            out.println("<p>Error: " + err);
    }
    
    public void handleAdminSetAnchor(SessionInfo sessionInfo, PrintWriter out, int userId) throws Exception {
        if (sessionInfo == null) { printNoSession(out); return; }
        
        ServerInterface api = getAPI();
        int err = api.setAnchor(sessionInfo.sessionId, userId, true);
        if (err == anubis.Error.OK)
            out.println("<p>OK.");
        else
            out.println("<p>Error: " + err);
    }
    
    // Don't know how to handle it
    public void handleDefault(PrintWriter out, String uri) {
        
        out.println("<p><br><br>");
        out.println("<p>Page not found: " + uri);
        out.println("<p><a href=\"/\">Return to the main page</a>.");
    }
    
    // ======================================================================
    // This is the root GET/POST handler.
    // ======================================================================
    
    void printHeader(PrintWriter out) {
        out.println("<!DOCTYPE html>");
        out.println("<html>");
        out.println("<head>");
        out.println("<title>democratic.money</title>");
        out.println("</head>");
        out.println("<body style=\"text-align:center;margin:0;padding:0;background:#D0D6E0\">"); 
    }
    
    void printMainDivOpen(PrintWriter out) {
        out.println("<div style=\"background:white;color:black;width:900px;text-align:left;margin:0 auto;padding:20px\">");
    }

    /**
     * Inner method used by processRequest to be able to retry once if 
     *   this method blows up with a ConnectException due to our magic 
     *   per-worker-thread RMI stub blowing up because the Anubis RMI 
     *   server object got restarted.
     * 
     * The only thing to watch out for here is that any modifications to
     *   the "response" argument object should only be made AFTER ALL
     *   calls to the ServerInterface (the RMI server).
     *  
     * These calls are made inside the handlers of specific URIs
     *   (printHome(), printDefault(), etc.)
     */
    protected void processRequestInner(HttpServletRequest request, HttpServletResponse response, PrintWriter out) 
            throws ServletException, IOException, Exception
    {
        request.setCharacterEncoding("UTF-8");
        response.setContentType("text/html;charset=UTF-8");

        // URI
        String uri = removeTrailingSlashes(request.getRequestURI());
        
        // Check if user thinks they have an active session
        SessionInfo sessionInfo = null;
        long sessionId = getCookieSessionId(request);
        if (sessionId >= 0) {
            // We got a session cookie...
            ServerInterface api = getAPI();
            sessionInfo = api.getSessionInfo(sessionId);
            if (sessionInfo != null) {
                // ..and it isn't expired.
                sessionInfo.sessionId = sessionId;
            }
        }
        
        // ================================================================
        //  HTML Header
        // ================================================================
        
        printHeader(out);
        
        // ================================================================
        //  Top stripe
        // ================================================================

        out.println("<div style=\"display:table;background:#E6EEF2;color:black;width:900px;text-align:left;margin:0 auto;padding:20px\">");
        out.println("<table with=100% align=left cellpadding=0 cellspacing=8><tr>");

        if ((! uri.equals("/logout")) && (sessionInfo != null)) {
            
            // Logged-in top stripe

            // Get first name for display
            int idx = sessionInfo.name.indexOf(' ');
            String firstName = sessionInfo.name;
            if (idx >= 0)
                firstName = firstName.substring(0, idx);
            out.println("<td colspan=99><nobr>Hi <b>" + firstName + "</b>! You have <b>" + Main.formatMoneyTicker(sessionInfo.unlockedBalance) + "</b>. </nobr></td></tr><tr>");

            // home page button
            out.println("<td><form action=\"/\" method=\"get\">");
            out.println("<input type=\"submit\" value=\"My account\"></form></td>");
            // Send money button
            out.println("<td><form action=\"/sendMoneyForm\" method=\"get\">");
            out.println("<input type=\"submit\" value=\"Send money\"></form></td>");
            // Send money button
            out.println("<td><form action=\"/burnMoneyForm\" method=\"get\">");
            out.println("<input type=\"submit\" value=\"Burn money\"></form></td>");
            // Find user button
            out.println("<td><form action=\"/findUser\" method=\"get\">");
            out.println("<input type=\"submit\" value=\"Find user\"></form></td>");
            // Manage invites button
            out.println("<td><form action=\"/manageInvites\" method=\"get\">");
            out.println("<input type=\"submit\" value=\"Invitations\"></form></td>");
            // Edit profile button
            out.println("<td><form action=\"/settings\" method=\"get\">");
            out.println("<input type=\"submit\" value=\"Settings\"></form></td>");
            // logout button
            out.println("<td><form action=\"/logout\" method=\"post\">");
            out.println("<input type=\"submit\" value=\"Sign out\"></form></td>");

        } else {
            
            // Not logged-in top stripe
            out.println("<td height=30></td>");
        }
        
        out.println("</tr></table></div>");
            
        // ================================================================
        //  Route the request
        // ================================================================

        printMainDivOpen(out);

        if ((sessionId >= 0) && (sessionInfo == null)) {
            handleSessionExpired(response, out);
//        } else if (uri.equals("/tick")) { // FIXME: REMOVEME TESTING
//            handleTick(out);
        } else if (uri.length() == 0) {
            handleHome(sessionInfo, request, response, out);
        } else if (uri.equals("/user")) {
            handleUserGet(sessionInfo, request, out);
        } else if (uri.equals("/login")) {
            handleLogin(request, response, out);
        } else if (uri.equals("/logout")) {
            handleLogout(sessionInfo, request, response, out);
        } else if (uri.equals("/getPasswordResetCode")) {
            handleGetPasswordResetCode(request, out);
        } else if (uri.equals("/getPasswordResetCodeForm")) {
            handleGetPasswordResetCodeForm(out);
        } else if (uri.equals("/resetPassword")) {
            handleResetPassword(request, out);
        } else if (uri.startsWith("/resetPasswordForm/")) {
            long resetCode;
            String resetCodeStr = uri.substring(19);
            try { resetCode = Long.parseLong(resetCodeStr); } catch (NumberFormatException e) { printInvalidNumberError("resetCode", resetCodeStr, out); return; }
            handleResetPasswordForm(out, resetCode);
        } else if (uri.equals("/settings")) {
            handleSettings(sessionInfo, out);
        } else if (uri.equals("/editSettings")) {
            handleEditSettings(sessionInfo, request, out);
        } else if (uri.equals("/changePassword")) {
            handleChangePassword(sessionInfo, request, out);
        } else if (uri.equals("/deleteAccount")) {
            handleDeleteAccount(sessionInfo, request, response, out);
        } else if (uri.equals("/deleteAccountConfirmationDialog")) {
            handleDeleteAccountConfirmationDialog(sessionInfo, request, out);
        } else if (uri.equals("/sendMoney")) {
            handleSendMoney(sessionInfo, request, out);
        } else if (uri.equals("/sendMoneyForm")) {
            handleSendMoneyForm(sessionInfo, out);
        } else if (uri.equals("/sendMoneyConfirmationDialog")) {
            handleSendMoneyConfirmationDialog(sessionInfo, request, out);
        } else if (uri.equals("/burnMoney")) {
            handleBurnMoney(sessionInfo, request, out);
        } else if (uri.equals("/burnMoneyForm")) {
            handleBurnMoneyForm(sessionInfo, out);
        } else if (uri.equals("/burnMoneyConfirmationDialog")) {
            handleBurnMoneyConfirmationDialog(sessionInfo, request, out);
        } else if (uri.equals("/findUser")) {
            handleFindUser(sessionInfo, out);
        } else if (uri.startsWith("/addValidation/")) {
            int userId;
            String userIdStr = uri.substring(15);
            try { userId = Integer.parseInt(userIdStr); } catch (NumberFormatException e) { printInvalidNumberError("userId", userIdStr, out); return; }
            handleAddValidation(sessionInfo, request, out, userId);
        } else if (uri.startsWith("/removeValidation/")) {
            int userId;
            String userIdStr = uri.substring(18);
            try { userId = Integer.parseInt(userIdStr); } catch (NumberFormatException e) { printInvalidNumberError("userId", userIdStr, out); return; }
            handleRemoveValidation(sessionInfo, request, out, userId, true, true);
        } else if (uri.equals("/requestTrust")) {
            handleRequestTrust(sessionInfo, request, out);
        } else if (uri.equals("/voteTrust")) {
            handleVoteTrust(sessionInfo, request, out);
        } else if (uri.equals("/challengeTrust")) {
            handleChallengeTrust(sessionInfo, request, out);
        } else if (uri.equals("/challengeTrustConfirmationDialog")) {
            handleChallengeTrustConfirmationDialog(sessionInfo, request, out);
        } else if (uri.startsWith("/manageInvites")) {
            handleManageInvites(sessionInfo, out);
        } else if (uri.startsWith("/newInvite")) {
            handleNewInvite(sessionInfo, request, out);
        } else if (uri.startsWith("/cancelInvite/")) {
            long invitationCode;
            String invitationCodeStr = uri.substring(14);
            try { invitationCode = Long.parseLong(invitationCodeStr); } catch (NumberFormatException e) { printInvalidNumberError("invitationCode", invitationCodeStr, out); return; }
            handleCancelInvite(request, out, invitationCode);
        } else if (uri.startsWith("/invite/")) {
            long invitationCode;
            String invitationCodeStr = uri.substring(8);
            try { invitationCode = Long.parseLong(invitationCodeStr); } catch (NumberFormatException e) { printInvalidNumberError("invitationCode", invitationCodeStr, out); return; }
            handleCheckInvite(out, invitationCode);
        } else if (uri.equals("/acceptInvite")) {
            handleAcceptInvite(request, out);            
        } else if (uri.equals("/adminShutdown")) {
            handleAdminShutdown(sessionInfo, out);
        } else if (uri.startsWith("/adminSetAnchor/")) {
            int userId;
            String userIdStr = uri.substring(16);
            try { userId = Integer.parseInt(userIdStr); } catch (NumberFormatException e) { printInvalidNumberError("userId", userIdStr, out); return; }
            handleAdminSetAnchor(sessionInfo, out, userId);
        } else
            handleDefault(out, uri);
        
        out.println("<br><br><br>");
        out.println("</div>");
            
        // ================================================================
        //  Bottom stripe
        // ================================================================

        out.println("<div style=\"background:#E6EEF2;color:black;width:900px;height:55px;text-align:left;margin:0 auto;padding:20px\">");
        out.println("</div>");

        // ================================================================
        //  HTML Footer
        // ================================================================
            
        out.println("</body>");
        out.println("</html>");
    }

    /**
     * Processes requests for both HTTP <code>GET</code> and <code>POST</code>
     * methods.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    protected void processRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException 
    {
        ++totalWebHits;
        
        // If it is a static file that exists in the project, serve that.
        String s = getServletContext().getRealPath(request.getRequestURI());
        Path p = Paths.get(s);
        if (Files.isRegularFile(p)) {
            String mime = getServletContext().getMimeType(s);
            if (mime == null) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                return;
            }
            response.setContentType(mime);
            File file = new File(s);
            response.setContentLength((int)file.length());
            FileInputStream in = new FileInputStream(file);
            OutputStream out = response.getOutputStream();
            byte[] buf = new byte[65536];
            int count = 0;
            while ((count = in.read(buf)) >= 0) {
                out.write(buf, 0, count);
            }
            out.close();
            in.close();
            return;
        }

        // Not a file, so run the request URI string handler.
        StringWriter stringWriter = new StringWriter();
        PrintWriter out = new PrintWriter(stringWriter);
        try {
            try {
                processRequestInner(request, response, out);
            } catch (ConnectException | NoSuchObjectException e) {
                // May be a stale RMI stub, so get rid of it.
                // Happens when the Anubis server shuts down and restarts while
                //   the web servers don't.
                deleteAPI();
                
                // Restart the answer buffer.
                out.close();
                stringWriter = new StringWriter();
                out = new PrintWriter(stringWriter);
                
                // Try again, ONCE more.
                processRequestInner(request, response, out);
            }
        } catch (ConnectException e) {

            // Log it
            getServletContext().log("ERROR: uncaught exception", e);
            
            // Restart the answer buffer.
            out.close();
            stringWriter = new StringWriter();
            out = new PrintWriter(stringWriter);
            
            printHeader(out);
            printMainDivOpen(out);

            // RMI server is offline.
            out.println("<center><p><br><br>Web server cannot connect to master server.<p>It is probably down for maintenance, and should come back up ASAP.<p>Please try again later.<p><br><br></center>");
            out.println("</div></body></html>");
            
        } catch (Exception e) {
            
            // Restart the answer buffer.
            out.close();
            stringWriter = new StringWriter();
            out = new PrintWriter(stringWriter);
            
            printHeader(out);
            printMainDivOpen(out);
            
            out.println("<center><p><br><br>An internal error has occurred. Please try again later.<p><br><br></center>");
            out.println("<p><hr size=1><p>Error information:<p><tt>");
            e.printStackTrace(out);
            out.println("</tt>");
            out.println("</div></body></html>");
            
        } finally {
            out.close();
            
            // Flush our string buffer to the response writer.
            response.setContentType("text/html;charset=UTF-8");
            try (PrintWriter rpw = response.getWriter()) {
                rpw.print(stringWriter.getBuffer().toString());
                rpw.close();
            }
        }
    }

    // <editor-fold defaultstate="collapsed" desc="HttpServlet methods. Click on the + sign on the left to edit the code.">
    /**
     * Handles the HTTP <code>GET</code> method.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    /**
     * Handles the HTTP <code>POST</code> method.
     *
     * @param request servlet request
     * @param response servlet response
     * @throws ServletException if a servlet-specific error occurs
     * @throws IOException if an I/O error occurs
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    /**
     * Returns a short description of the servlet.
     *
     * @return a String containing servlet description
     */
    @Override
    public String getServletInfo() {
        return "Short description";
    }// </editor-fold>

}
