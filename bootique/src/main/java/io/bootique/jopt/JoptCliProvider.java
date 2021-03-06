package io.bootique.jopt;

import com.google.inject.Inject;
import com.google.inject.Provider;
import io.bootique.BootiqueException;
import io.bootique.annotation.Args;
import io.bootique.cli.Cli;
import io.bootique.command.Command;
import io.bootique.command.CommandManager;
import io.bootique.log.BootLogger;
import io.bootique.meta.application.ApplicationMetadata;
import io.bootique.meta.application.OptionMetadata;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpecBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;

public class JoptCliProvider implements Provider<Cli> {

    private String[] args;
    private BootLogger bootLogger;
    private ApplicationMetadata application;
    private Provider<CommandManager> commandManagerProvider;


    @Inject
    public JoptCliProvider(BootLogger bootLogger,
                           Provider<CommandManager> commandManagerProvider,
                           ApplicationMetadata application,
                           @Args String[] args) {

        // injecting CommandManager via provider for an obscure reason - it is injected here and also in
        // ApplicationMetadata provider (and this class depends on ApplicationMetadata). So when there's an error
        // during CommandManager construction, it is thrown twice, causing ProvisionException to lose its "cause",
        // complicating exception analysis.

        this.commandManagerProvider = commandManagerProvider;
        this.application = application;
        this.args = args;
        this.bootLogger = bootLogger;
    }

    @Override
    public Cli get() {
        OptionParser parser = createParser();
        OptionSet parsed;
        try {
            parsed = parser.parse(args);
        } catch (OptionException e) {
            throw new BootiqueException(1, e.getMessage(), e);
        }

        String commandName = commandName(parsed);

        return new JoptCli(bootLogger, parser, parsed, commandName);
    }

    protected OptionParser createParser() {

        // do not allow option abbreviations .. we will provide short forms explicitly
        OptionParser parser = new OptionParser(false);

        application.getCommands().forEach(c -> {

            c.getOptions().forEach(o -> addOption(parser, o));

            // using option-bound command strategy...
            OptionMetadata commandAsOption = c.asOption();
            addOption(parser, commandAsOption);
        });

        // load global options
        application.getOptions().forEach(o -> addOption(parser, o));
        return parser;
    }

    protected void addOption(OptionParser parser, OptionMetadata option) {

        // ensure non-null description
        String description = Optional.ofNullable(option.getDescription()).orElse("");

        // TODO: how do we resolve short name conflicts?
        List<String> longAndShort = asList(option.getShortName(), option.getName());
        OptionSpecBuilder optionBuilder = parser.acceptsAll(longAndShort, description);
        switch (option.getValueCardinality()) {
            case OPTIONAL:
                optionBuilder.withOptionalArg().describedAs(option.getValueName());
                break;
            case REQUIRED:
                optionBuilder.withRequiredArg().describedAs(option.getValueName());
            default:
                break;
        }
    }

    // using option-bound command strategy...
    protected String commandName(OptionSet optionSet) {

        Map<String, Command> matches = new HashMap<>(3);
        commandManagerProvider.get().getCommands().forEach((name, c) -> {
            if (optionSet.has(name) && !optionSet.hasArgument(name)) {
                matches.put(name, c);
            }
        });

        switch (matches.size()) {
            case 0:
                // default command should be invoked
                return null;
            case 1:
                return matches.keySet().iterator().next();
            default:
                String opts = matches.keySet().stream().collect(joining(", "));
                String message = String.format("CLI options match multiple commands: %s.", opts);
                throw new BootiqueException(1, message);
        }
    }
}
