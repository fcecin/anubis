# anubis
An Universal Basic Income Server

This is the implementation of the service running at https://democratic.money .

Anubis and AnubisClient are public domain software ( see http://unlicense.org/ ).

Anubis is a Java J2SE command-line application that acts as the central server. It runs an RMI server, and uses 
Prevayler to persist all of the core server-side data.

AnubisClient is a monolithic Java Servlet that implements an entire web application. It takes in requests from users 
(web clients) and translates them into RMI calls to the Anubis core server. AnubisClient is completely stateless, 
and you can run any number of web servers to handle clients and interface with the central server.

Both projets were developed with Netbeans 8 with J2EE support (for servlets). Netbeans 8 J2EE comes with Tomcat 8, 
which is a stand-alone web server ("Coyote") integrated with a servlet container ("Catalina"), which makes testing 
really easy. However, you should be able to deploy your server with any servlet container and web server combination.

The non-standard library JARs used are included in the projects' /lib folders. 
You should be able to open both nbproject folders and compile the projects immediately. 
The libraries used are licensed under their respective licenses:

* Prevayler: http://prevayler.org/
* EdDSA-java: https://github.com/str4d/ed25519-java
* JBcrypt: http://www.mindrot.org/projects/jBCrypt/
* Javamail: https://javaee.github.io/javamail/

Actually, JBcrypt was a single .java file, so it was faster to just paste it into the project source tree, instead 
of carrying around another external .jar dependency.

If you need help with this software, contact me directly ( fcecin AT gmail DOT com ) and I'll help you out.
