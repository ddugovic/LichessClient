package org.chernovia.chess;

import chariot.Client;
import chariot.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.java_websocket.client.WebSocketClient;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class LichessTVLogger extends Thread implements LichessTVWatcher {
    String clientID;
    Client client;
    GameBase gameBase;
    Enums.Channel channel = Enums.Channel.blitz; //Enums.Channel.bullet;
    Map<String, LichessTVSock> tvClients = new HashMap<>();
    static Logger logger = Logger.getLogger(LichessTVLogger.class.getName());
    static FileHandler logFileHandler;
    static File logFile;
    long lastPurge = System.currentTimeMillis();
    boolean logging;
    boolean running = true;
    String dbUri, dbUsr, dbPwd, dbName;

    public static class ChariotException extends Exception {
        public ChariotException(int statusCode, String msg) {
            super("Chariot Goof: " + msg + ", status: " + statusCode);
        }
    }

    public static void main(String[] args) { //throws URISyntaxException, IOException {
        try {
            new LichessTVLogger(args[0],args[1],args[2],args[3]).start();
        }
        catch (Exception e) {
            log2File(Level.SEVERE,"Zoiks: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public LichessTVLogger(String uri, String usr, String pwd, String db) {
        dbUri = uri; dbUsr = usr; dbPwd = pwd; dbName = db;
    }

    public void reconnectToDB(long pauseTime) {
        pause(pauseTime);
        gameBase = new GameBase(dbUri,dbUsr,dbPwd,dbName);
    }

    public void run() {
        try {
            while(running) {
                gameBase = new GameBase(dbUri,dbUsr,dbPwd,dbName);
                clientID = "lichess_logger" + (int)(Math.random() * 999);
                client = Client.basic();
                log2File(Level.INFO,"Starting: " + clientID);
                logging = watchTVGames();
                do pause(60 * 1000); while (logging && countGames() > 0);
                log2File(Level.INFO,"Rebooting: " + clientID);
                pause(8000);
            }
        } catch (Exception e) {
            log2File(Level.SEVERE,"Zoiks: " + e.getMessage()); e.printStackTrace();
        }
    }

    public synchronized long countGames() {
        long tvGames = tvClients.values().stream().filter(WebSocketClient::isOpen).count();
        log2File(Level.INFO,"Currently watched games: " + tvGames);
        return tvGames;
    }

    public void pause(long t) {
        try { Thread.sleep(t); } catch (InterruptedException ignore) {}
    }

    public void purgeCheck() {
        if (System.currentTimeMillis() - lastPurge > 15 * 60 * 1000) {
            log2File(Level.INFO,"Purging: " + clientID);
            tvClients.keySet().stream().filter(k -> tvClients.get(k).isClosed()).forEach(id -> tvClients.remove(id));
            lastPurge = System.currentTimeMillis();
        }
    }

    public void followGame(String id) {
        pause(1000);
        try {
            LichessTVSock client = new LichessTVSock(id,this);
            if (tvClients.put(id, client) == null) client.connect();
        }
        catch (URISyntaxException e) { log2File(Level.WARNING,"URI Augh: " + e.getMessage()); }
    }

    public boolean watchTVGames() {
        try {
            pause(1000);
            loadTV().stream().filter(id -> tvClients.get(id) == null).forEach(this::followGame);
            purgeCheck();
            return true;
        }
        catch (Exception oops) {
            if (oops instanceof ChariotException) {
                log2File(Level.WARNING,"Chariot Goof: " + oops.getMessage());
            } else {
                log2File(Level.WARNING,"Unexpected Exception: " + oops.getMessage());
            }
        }
        return false;
    }

    public int calcAvgRating(Pgn pgn) {
        int bRat, wRat;
        String blackElo = pgn.tagMap().get("BlackElo");
        if (blackElo != null) {
            try { bRat = Integer.parseInt(blackElo); } catch (NumberFormatException e) { bRat = 0; }
        } else bRat = 0;
        String whiteElo = pgn.tagMap().get("WhiteElo");
        if (whiteElo != null) {
            try { wRat = Integer.parseInt(whiteElo); } catch (NumberFormatException e) { wRat = 0; }
        } else wRat = 0;
        return ((bRat + wRat) / 2);
    }

    public void addGameToDB(String id) throws Exception {
        One<Pgn> pgn = client.games().pgnByGameId(id);
        if (pgn instanceof Fail(int statusCode, var err)) throw
                new ChariotException(statusCode,"(pgnByGameID) " + err.message());
        One<Game> game = client.games().byGameId(id);
        if (game instanceof Fail(int statusCode, var err)) throw
                new ChariotException(statusCode,"(byGameID) " + err.message());
        if (pgn.isPresent() && game.isPresent()) {
            int avgRat = calcAvgRating(pgn.get());
            double flux = FluxCapacitor.calcFlux(game.get(), pgn.get());
            int crowd = tvClients.containsKey(id) ? tvClients.get(id).maxCrowd : 0;
            int moves = pgn.get().moveListSAN().size();
            int upset = calcUpset(game.get(), pgn.get());
            if (moves > 12) {
                log2File(Level.INFO,"Adding game: " + id + " (flux: " + flux + ", crowd: " + crowd + ", moves: " + moves + ")");
                gameBase.addGame(id, avgRat, upset, moves, crowd, flux, channel.name(), pgn.get().tagMap().get("UTCDate"));
            }
            else {
                log2File(Level.INFO,"Skipping short game: " + id);
            }
            log2File(Level.INFO,"Free memory: " + Runtime.getRuntime().freeMemory() / 1024);
        }
        else {
            log("Oops: PGN/Game not found: " + id);
        }
    }

    public Set<String> loadTV() throws ChariotException {
        Many<Game> games = client.games().byChannel(channel);
        if (games instanceof Fail(int statusCode, var err)) throw
                new ChariotException(statusCode, "(loadTV) " + err.message());
        return games.stream().map(Game::id).collect(Collectors.toSet());
    }

    @Override
    public void gameStarted(String id, JsonNode data) {}

    @Override
    public void movePlayed(String id, JsonNode data) {
        //log(data.toString());
    }

    @Override
    public void gameOver(String id, JsonNode data) {
        log("Game Over: " + id);
    }

    @Override
    public void gameClosed(String id) {
        log("Game Closed: " + id);
        try {
            addGameToDB(id);
            logging = watchTVGames();
        } catch (Exception e) {
            if (e instanceof ChariotException) log2File(Level.WARNING,"*** Chariot Goof ***");
            log2File(Level.WARNING,"Game insertion error (id "  +  id + "): " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static int calcUpset(Game game, Pgn pgn) {
        try {
            int bRat = Integer.parseInt(pgn.tagMap().get("BlackElo"));
            int wRat = Integer.parseInt(pgn.tagMap().get("WhiteElo"));
            Enums.Color winner = game.winner().get();
            if (winner == Enums.Color.white) return bRat - wRat;
            else if (winner == Enums.Color.black) return wRat - bRat;
            else return Math.abs(wRat - bRat)/4;
        } catch (NumberFormatException oops) { return 0; }
    }

    public static void updateLogfile() throws IOException {
        String fileName = ZonedDateTime.now(ZoneId.of("UTC")).format(DateTimeFormatter.ISO_DATE) + ".xml";
        if (logFileHandler == null || !logFile.getName().equals(fileName)) {
            log("Creating new log file: " + fileName);
            logFile = new File("logs/" + fileName);
            if (logFile.exists() && logFile.delete()) log("Deleted old log file: " + fileName);
            if (logFile.exists() || logFile.createNewFile()) {
                log("Log file created: " + logFile.getAbsolutePath());
                if (logFileHandler != null) {
                    logger.removeHandler(logFileHandler);
                    logFileHandler.close();
                }
                logFileHandler = new FileHandler(logFile.getAbsolutePath());
                logger.addHandler(logFileHandler);
            }
        }
    }

    public static void log2File(Level level, String message) {
        try {
            updateLogfile();
            logger.log(level,message);
        } catch (IOException e) {
            System.err.println(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static void log(String str) { log(str,true); }
    public static void log(String str, boolean cr) {
        if (cr) logger.log(Level.INFO,str); else System.out.print(str);
    }

}
