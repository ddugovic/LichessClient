package org.chernovia.chess;

import chariot.model.Enums;
import chariot.model.Game;
import chariot.model.Pgn;
import com.github.bhlangonijr.chesslib.Board;

import java.util.Map;

import static java.util.Map.entry;

public class FluxCapacitor {
    static final Map<Character, Integer> pieceVals = Map.ofEntries(
            entry('p', -1),entry('P', 1),
            entry('n', -3),entry('N', 3),
            entry('b', -3),entry('B', 3),
            entry('r', -5),entry('R', 5),
            entry('q', -9),entry('Q', 9)
    );
    public static double calcFlux(Game game, Pgn pgn) {
        int ply = 0, maxEval = 0, minEval = 0, prevEval = 0, streak = 0, wTime = 999, bTime = 999;
        Board board = new Board();
        for (String move : pgn.moveListSAN()) { //GameCenter.log("Move: " + move);
            if (game.clocks().size() > ply) {
                int t =  game.clocks().get(ply)/100;
                if ((ply++) % 2 == 0) wTime = t; else bTime = t;
            } //GameCenter.log("Clock times -> wc: " + wTime + ", bc: " + bTime);
            if (wTime < 30 || bTime < 30) break;
            board.doMove(move);
            int eval = getEval(board);
            if (prevEval == eval) {
                if (streak++ > 3) {
                    if (eval > maxEval) maxEval = eval;
                    else if (eval < minEval) minEval = eval;
                }
            }
            else { streak = 0; prevEval = eval; }
        }
        if (game.winner().isPresent()) { //GameCenter.log("Winner: " + game.winner().get() + ", Max: " + maxEval + ", Min: " + minEval);
            if (game.winner().get().equals(Enums.Color.white)) return Math.abs(minEval);
            if (game.winner().get().equals(Enums.Color.black)) return maxEval;
        }
        return (Math.max(Math.abs(minEval),Math.abs(maxEval)))/2d;
    }
    private static int getEval(Board board) {
        String fen = board.getFen().split(" ")[0];
        return fen.chars().mapToObj(i -> (char)i)
                .filter(pieceVals::containsKey)
                .map(pieceVals::get)
                .reduce(0, Integer::sum);
    }
}
