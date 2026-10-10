import forge.GuiDesktop;
import forge.gui.GuiBase;
import forge.model.FModel;

import java.io.*;
import java.nio.file.*;

/**
 * A long-lived game worker for iterate/engine.py: starts Forge once, then plays one game per line read from stdin,
 * exactly as MatchGames plays it (MatchGames.playOne), appending the game's JSON line to the given file.
 *
 * Input, one task per line, tab-separated: TAG  DECK_A  DECK_B  SEED  GAME_INDEX  TIMEOUT_S  OUT.jsonl
 * Output on stdout: "@@READY" once Forge is loaded, then "@@DONE TAG" (or "@@FAIL TAG message") per task. Forge's own
 * logging also goes to stdout; the scheduler only reads lines starting with "@@". After a game that did not finish
 * (timeout) the worker exits: the abandoned game's thread may still be running and drawing from Forge's shared random
 * generator (MyRandom), which made the next game in the same JVM unreproducible (runs/engine-test-1, seed 9600000
 * games 4-5); the engine restarts it.
 */
public class MatchWorker {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        PrintStream out = System.out;
        out.println("@@READY");
        out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            String[] f = line.split("\t");
            String tag = f[0];
            try {
                Path a = Paths.get(f[1]);
                String nameA = a.getFileName().toString().replaceAll("\\.dck$", "");
                String json = MatchGames.playOne(a, nameA, Paths.get(f[2]), Long.parseLong(f[3]), Integer.parseInt(f[4]),
                        Integer.parseInt(f[5]));
                try (PrintWriter w = new PrintWriter(new FileWriter(f[6], true))) {
                    w.println(json);
                }
                out.println("@@DONE " + tag);
                if (!json.contains("\"end\":\"finished\"")) {
                    // a timed-out (or failed) game's thread can keep running and drawing from Forge's shared random
                    // generator, which would change the next game this JVM plays: exit, so the engine starts a fresh one
                    out.flush();
                    System.exit(0);
                }
            } catch (Exception ex) {
                out.println("@@FAIL " + tag + " " + ex);
            }
            out.flush();
        }
        System.exit(0);
    }
}
