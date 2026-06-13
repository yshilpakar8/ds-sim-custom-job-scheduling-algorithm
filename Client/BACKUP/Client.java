import java.io.*;
import java.net.*;
import java.util.*;

public class Client {

    // -----------------------------------------------------------------------
    // Inner classes
    // -----------------------------------------------------------------------

    static class Server {
        String type;
        int id;
        String state;   // inactive, booting, idle, active, unavailable
        int boot, cores, memory, disk, waitingJobs, runningJobs;

        int waitTime = 0;

        Server(String type, int id, String state, int boot, int cores, int memory, int disk, int waitingJobs, int runningJobs) {
            this.type        = type;
            this.id          = id;
            this.state       = state;
            this.boot        = boot;
            this.cores       = cores;
            this.memory      = memory;
            this.disk        = disk;
            this.waitingJobs = waitingJobs;
            this.runningJobs = runningJobs;
        }


        int totalJobs() {
            return waitingJobs + runningJobs;
        }

        boolean isIdle()     { return state.equals("idle"); }
        boolean isActive()   { return state.equals("active"); }
        boolean isInactive() { return state.equals("inactive"); }
        boolean isBooting()  { return state.equals("booting"); }

        int statePriority() {
            if (isIdle())     return 0;
            if (isActive())   return 1;
            if (isBooting())  return 2;
            return 3; // inactive / unavailable
        }
    }


    static class Job {
        int submitTime, id, state, estRuntime, cores, memory, disk;

        String serverType;
        int serverID;

        Job(int id, int submitTime, int cores, int memory, int disk, int estRuntime) {
            this.submitTime  = submitTime;
            this.id          = id;
            this.cores       = cores;
            this.memory      = memory;
            this.disk        = disk;
            this.estRuntime  = estRuntime;
        }
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private static final String HOST    = "localhost";
    private static final int    PORT    = 50000;

    private Socket       socket;
    private BufferedReader in;
    private PrintWriter    out;


    // Map of all servers - populated at startup
    HashMap<String, ArrayList<Server>> allServers = new HashMap<String, ArrayList<Server>>();

    // Map of jobs scheduled - used for runtime penalty 
    HashMap<Integer, ArrayList<Job>> jobList = new HashMap<Integer, ArrayList<Job>>();

    // -----------------------------------------------------------------------
    // Main / Run
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        new Client().run();
    }

    private void run() throws Exception {
        connect();

        send("HELO");
        recv(); // OK

        send("AUTH " + System.getProperty("user.name"));
        recv(); // OK

        allServers = getAllServers();

        send("REDY");
        String msg = recv();

        while (!msg.equals("NONE") && !msg.startsWith("ERR")) {
            if (msg.startsWith("JOBN")) {
                handleJob(parseJob(msg));
            } if(msg.startsWith("JCPL")) {
                handleCompletion(msg);
            }
            send("REDY");
            msg = recv(); // error
        }

        send("QUIT");
        recv(); 
        socket.close();
    }

    // -----------------------------------------------------------------------
    // Populate server hashmap function
    // -----------------------------------------------------------------------

    private HashMap<String, ArrayList<Server>> getAllServers() throws Exception {
        send("GETS All");
        String dataLine = recv();
        send("OK");

        String[] dataParts = dataLine.split(" ");
        int nRecs = Integer.parseInt(dataParts[1]);

        for(int i = 0; i < nRecs; i++) {
            String line = recv();
            Server s = parseServer(line);

            if(!allServers.containsKey(s)) {
                allServers.put(s.type, new ArrayList<Server>());
            }

            allServers.get(s.type).add(s);
        }

        send("OK");
        recv();


        return allServers;
    }

    // -----------------------------------------------------------------------
    // Job handling
    // -----------------------------------------------------------------------

    private void handleJob(Job job) throws Exception {
        Server chosen = null;
        List<Server> available = getAvailableServers(job);
        if(!available.isEmpty()){chosen = available.getFirst();}
        
        if(chosen == null) {
            chosen = bestServer(job);
        } 

        if(chosen == null) {
            List<Server> capable = getCapableServers(job);
            chosen = selectEarliestFinish(job, capable);
        }

        if(chosen == null) {
            chosen = largestServer();
        }

        send("SCHD " + job.id + " " + chosen.type + " " + chosen.id);
        String dataLine = recv();

        appendJobList(job, chosen);

       
        if(dataLine.split(" ")[0] != "ERR") {
            chosen.waitTime += job.estRuntime;
        } 
        
    }

    void appendJobList(Job job, Server chosen) {
        jobList.put(job.serverID, new ArrayList<Job>());
        jobList.get(job.serverID).add(job);

        
        List<Job> jobs = jobList.get(job.serverID);
        if (jobs == null) return;

        for (Job j : jobs) {
            if (j.id == job.id) {
                j.serverType = chosen.type;
                j.id = chosen.id;
                break;
            }
        }
        
    }

    private Server selectBestFit(Job job, List<Server> available) {
        Server best = null;
        int    bestJobs     = Integer.MAX_VALUE;
        int    bestPriority = Integer.MAX_VALUE;
        int    bestCores    = Integer.MAX_VALUE;

        for (Server s : available) {
            int jc = s.totalJobs();
            int pr = s.statePriority();

            if (best == null
                    || jc < bestJobs
                    || (jc == bestJobs && pr < bestPriority)
                    || (jc == bestJobs && pr == bestPriority && s.cores < bestCores)) {
                best         = s;
                bestJobs     = jc;
                bestPriority = pr;
                bestCores    = s.cores;
            }
        }
        return best;

    }

    private Server selectEarliestFinish(Job job, List<Server> capable) {
        Server best = null;
        long bestFinish = Long.MAX_VALUE;
        int bestCores = Integer.MAX_VALUE;

        for(Server s: capable) {
            if(best == null || s.waitTime < bestFinish
                || (s.waitTime == bestFinish && s.cores < bestCores) ){
                    best = s;
                    bestFinish = s.waitTime;
                    bestCores = s.cores;
                }
        }

        return best;
    }

    private Server largestServer() {
        Server best = null;
        for (List<Server> list : allServers.values()) {
            for (Server s : list) {
                if (best == null || s.cores > best.cores) best = s;
            }
        }
        return best;

    }

    private String bestServerType(Job job) {
        for(String type: allServers.keySet()) {
            int serverCores = allServers.get(type).get(0).cores;

            int coreDiff = serverCores - job.cores;
            
            if(coreDiff > 0) { return type; }
        }

        return null;
    }

    private Server bestServer(Job job) {
        String type = bestServerType(job);
        Server best = null;
        int bestWT = Integer.MAX_VALUE;

        List<Server> servers = allServers.get(type);

        if(servers == null) { return null;} 

        for(Server s: servers) {
            if(s.waitTime < bestWT) {
                bestWT = s.waitTime;
                best = s;
            }
        }

        return best;
    }

    private Server selectServer(Job job, List<Server> capable) throws Exception {
        Server best        = null;
        int    lowestJC = Integer.MAX_VALUE;
        long   bestFinish  = Long.MAX_VALUE;
        int    bestPriority = Integer.MAX_VALUE; 
        int    bestCores = Integer.MAX_VALUE; 
 
        for (Server s : capable) {
            
            int jc = s.totalJobs();
            //long waitTime = (long) getServerWaitTime(s.type, s.id);
            //long finish   = waitTime + (long) job.estRuntime;

            
            
            int priority = s.isIdle() ? 0 : 
                s.isActive()? 1 : s.isBooting() ? 2 : 3;

            //config010 - 7362 turnaround
            
            if(best == null || jc < lowestJC) {
                best         = s;
                //bestFinish   = finish;
                bestPriority = priority;
                bestCores    = s.cores;
                lowestJC     = jc;
            }
                // } else if (jc == lowestJC ) {
            //     if(finish < bestFinish) {
            //         best         = s;
            //         bestFinish   = finish;
            //         bestPriority = priority;
            //         bestCores    = s.cores;
            //         lowestJC     = jc;
            //     } else if (finish == bestFinish) {
            //         if(s.cores < bestCores) {
            //         best         = s;
            //         bestFinish   = finish;
            //         bestPriority = priority;
            //         bestCores    = s.cores;
            //         lowestJC     = jc;
            //         } else if (s.cores == bestCores) {
            //             if(priority < bestPriority) {
            //                 best         = s;
            //                 bestFinish   = finish;
            //                 bestPriority = priority;
            //                 bestCores    = s.cores;
            //                 lowestJC     = jc;
            //             }
            //         }
            //     }
            // } 
         }
        return best;
    }

    
    // -----------------------------------------------------------------------
    // Completion tracking
    // -----------------------------------------------------------------------

    private void handleCompletion(String msg) {
        // JCPL <endTime> <jobID> <serverType> <serverID>
        String[] parts = msg.split("\\s+");
        if (parts.length < 5) return;
        
        /*
        allservers -> servertype
            ->    List(severtype) -> serverID
        
        */

        int jobID = Integer.parseInt(parts[2]);
        String type = parts[3];
        int id = Integer.parseInt(parts[4]); 

        List<Server> servers = allServers.get(type);
        List<Job> jobs = jobList.get(jobID);
        if (servers == null) return;
        if (jobs == null) return;

        
        

        for (Server s : servers) {
            if (s.id == id) {
                s.waitTime -= jobs.get(jobID).estRuntime;
                break;
            }
        }

        
        
    }
    


    // -----------------------------------------------------------------------
    // Server queries
    // -----------------------------------------------------------------------
    private List<Server> getCapableServers(Job job) throws Exception {
        send("GETS Capable " + job.cores + " " + job.memory + " " + job.disk);
        String dataLine = recv(); // DATA <nRecs> <recLen>

        String[] dataParts = dataLine.split("\\s+");
        int nRecs = Integer.parseInt(dataParts[1]);

        send("OK");

        List<Server> servers = new ArrayList<>();
        for (int i = 0; i < nRecs; i++) {
            String line = recv();
            servers.add(parseServer(line));
        }

        send("OK");
        recv(); // .

        return servers;
    }


    private List<Server> getAvailableServers(Job job) throws Exception {
        send("GETS Avail " + job.cores + " " + job.memory + " " + job.disk);
        String dataLine = recv(); // DATA <nRecs> <recLen>

        String[] dataParts = dataLine.split("\\s+");
        int nRecs = Integer.parseInt(dataParts[1]);
        //recv();
        send("OK");
        
        List<Server> servers = new ArrayList<>();
        if(nRecs == 0) {
            recv();
            return servers;
        }


        
        for (int i = 0; i < nRecs; i++) {
            String line = recv();
            servers.add(parseServer(line));
        }

        
        send("OK");
        recv(); // .

        

        return servers;
    }

    public int getServerWaitTime(String serverType, int serverID) throws NumberFormatException, Exception {
        send(String.format("EJWT %s %d", serverType, serverID));
        return Integer.parseInt(recv());
    }

    // -----------------------------------------------------------------------
    // Parsing helpers
    // -----------------------------------------------------------------------

    private Job parseJob(String msg) {
        // JOBN  <jobID> <submitTime> <cores> <memory> <disk> <estRuntime>
        String[] p = msg.split(" ");

        // System.out.println("----------------------- NEW JOB -----------------------");
        // for(int i = 0; i < p.length; i++) {
        //     System.out.println("--------------");
        //     System.out.println(p[i]);
        // }

        
        int id = Integer.parseInt(p[1]);
        int submitTime = Integer.parseInt(p[2]);
        int core = Integer.parseInt(p[3]);
        int memory = Integer.parseInt(p[4]);
        int disk = Integer.parseInt(p[5]);
        int estRuntime = Integer.parseInt(p[6]);

        return new Job(id, submitTime, core, memory, disk, estRuntime);
    } 

    // Server(String type, int id, String state, int boot, int cores, int memory, int disk, int waitingJobs, int runningJobs) {
    private Server parseServer(String line) {
        // <serverType> <serverID> <state> <boot> <cores> <memory> <disk>
        String[] p = line.split("\\s+");
        // System.out.println("----------------------- NEW SERVER -----------------------");
        // for(int i = 0; i < p.length; i++) {
        //     System.out.println("--------------");
        //     System.out.println(p[i]);
        // }



        return new Server(
                p[0],
                Integer.parseInt(p[1]),
                p[3],
                Integer.parseInt(p[4]),
                Integer.parseInt(p[5]),
                Integer.parseInt(p[6]),
                Integer.parseInt(p[7]),
                Integer.parseInt(p[8]),
                Integer.parseInt(p[9])
        );
    }

    // -----------------------------------------------------------------------
    // Network I/O
    // -----------------------------------------------------------------------

    private void connect() throws Exception {
        socket = new Socket(HOST, PORT);
        in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
    }

    private void send(String msg) {
        out.println(msg);
        //System.out.println("SENDING: " + msg);
    }

    private String recv() throws Exception {
        String line = in.readLine();
        if (line == null) throw new EOFException("Connection closed by ds-server");

        //System.out.println("READING: " + line);
        return line.trim();
    }

    
}
