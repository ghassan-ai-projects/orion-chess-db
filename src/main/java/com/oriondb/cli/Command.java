package com.oriondb.cli;

/**
 * One CLI command.
 *
 * @param name    what the user types
 * @param usage   argument synopsis (without the command name)
 * @param summary one line for the command list
 * @param details longer help: options and examples
 * @param spec    accepted flags
 * @param handler the implementation; returns the exit code
 */
record Command(String name, String usage, String summary, String details, Args.Spec spec, Handler handler) {

    @FunctionalInterface
    interface Handler {
        int run(Args args, Context ctx) throws Exception;
    }

    String synopsis() {
        return "oriondb " + name + (usage.isEmpty() ? "" : " " + usage);
    }
}
