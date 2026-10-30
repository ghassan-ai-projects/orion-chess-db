package com.oriondb.cli;

import com.oriondb.db.Database;
import com.oriondb.query.Query;
import com.oriondb.query.QueryParser;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/** Streams and shared helpers handed to every command. */
final class Context {
    final InputStream in;
    final PrintStream out;
    final PrintStream err;

    Context(InputStream in, PrintStream out, PrintStream err) {
        this.in = in;
        this.out = out;
        this.err = err;
    }

    // ------------------------------------------------------------ databases

    /** Opens an existing database without taking the write lock (works while another process writes). */
    Database openReadOnly(String file) {
        Path p = existing(file);
        return Database.open(p, Database.Options.READ_ONLY);
    }

    /** Opens an existing database for writing (takes the process lock). */
    Database openWritable(String file) {
        Path p = existing(file);
        return Database.open(p, Database.Options.EXISTING);
    }

    /** Opens a database for writing, creating an empty one if missing. */
    Database openOrCreate(String file) {
        return Database.open(Path.of(file), Database.Options.DEFAULT);
    }

    static Path existing(String file) {
        Path p = Path.of(file);
        if (!Files.exists(p)) throw new CliException("no such database: " + file);
        if (Files.isDirectory(p)) throw new CliException(file + " is a directory, not a PGN database");
        return p;
    }

    /** Saves a mutated database and reports it. */
    void save(Database db) {
        if (!db.isDirty()) {
            err.println("no changes; nothing saved");
            return;
        }
        db.save();
    }

    /** One line on stderr when the database holds games that failed to parse. */
    void noteInvalid(Database db) {
        int n = db.invalidGames().size();
        if (n > 0) {
            err.println("note: " + n + " game" + (n == 1 ? "" : "s") + " could not be parsed and "
                + (n == 1 ? "is" : "are") + " skipped; run 'oriondb check " + db.path() + "'");
        }
    }

    // ------------------------------------------------------------ parsing

    /** Query-language text (bad terms are usage errors). */
    static Query query(Collection<String> terms) {
        return query(String.join(" ", terms));
    }

    static Query query(String text) {
        try {
            return QueryParser.parse(text == null ? "" : text);
        } catch (IllegalArgumentException e) {
            throw new UsageException("bad query: " + e.getMessage() + " (see 'oriondb help search')");
        }
    }

    /** "1,2,5-7" (also several arguments) → sorted distinct ids. */
    static List<Integer> ids(Collection<String> specs) {
        TreeSet<Integer> out = new TreeSet<>();
        for (String spec : specs) {
            for (String part : spec.split(",")) {
                String p = part.strip();
                if (p.isEmpty()) continue;
                try {
                    int dash = p.indexOf('-', 1);
                    if (dash > 0) {
                        int a = Integer.parseInt(p.substring(0, dash).strip()), b = Integer.parseInt(p.substring(dash + 1).strip());
                        if (b < a || b - a > 10_000_000) throw new UsageException("bad id range '" + p + "'");
                        for (int i = a; i <= b; i++) out.add(i);
                    } else {
                        out.add(Integer.parseInt(p));
                    }
                } catch (NumberFormatException e) {
                    throw new UsageException("bad game id '" + p + "' (use e.g. 3 or 1,4,9 or 10-20)");
                }
            }
        }
        if (out.isEmpty()) throw new UsageException("no game ids given");
        return new ArrayList<>(out);
    }

    static int id(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            throw new UsageException("bad game id '" + s + "'");
        }
    }
}
