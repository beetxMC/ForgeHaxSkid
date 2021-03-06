package com.matt.forgehax.mods.commands;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.matt.forgehax.Helper;
import com.matt.forgehax.mods.services.ChatCommandService;
import com.matt.forgehax.util.command.Command;
import com.matt.forgehax.util.command.CommandBuilders;
import com.matt.forgehax.util.command.ExecuteData;
import com.matt.forgehax.util.command.Options;
import com.matt.forgehax.util.command.exception.CommandExecuteException;
import com.matt.forgehax.util.mod.CommandMod;
import com.matt.forgehax.util.mod.loader.RegisterMod;
import com.matt.forgehax.util.serialization.ISerializableJson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import joptsimple.OptionParser;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.apache.commons.lang3.ArrayUtils;
import org.lwjgl.input.Keyboard;

// TODO: hold macros
@RegisterMod
public class MacroCommand extends CommandMod {
  
  public MacroCommand() {
    super("MacroCommand");
  }
  
  public final Options<MacroEntry> MACROS =
    GLOBAL_COMMAND
      .builders()
      .<MacroEntry>newOptionsBuilder()
      .name("macros")
      .description("Registered macros")
      .supplier(ArrayList::new)
      .factory(MacroEntry::new)
      .build();
  
  // which command in the list of commands to execute next
  private final Map<MacroEntry, Integer> macroIndex = new HashMap<>();
  
  @SubscribeEvent
  public void onKeyboardEvent(InputEvent.KeyInputEvent event) {
    MACROS
      .stream()
      .filter(macro -> macro.getBind().isPressed())
      .forEach(this::executeMacro);
  }
  
  private void removeMacro(MacroEntry macro) {
    MACROS.remove(macro);
    
    MC.gameSettings.keyBindings =
      ArrayUtils.remove(
        MC.gameSettings.keyBindings,
        ArrayUtils.indexOf(MC.gameSettings.keyBindings, macro.getBind()));
  }
  
  @Override
  public void onLoad() {
    super.onLoad();
    // MACROS.deserializeAll();
    
    MACROS
      .builders()
      .newCommandBuilder()
      .name("remove")
      .description("Remove a macro (Usage: \".macros remove --name hack (and/or) --key f\") ")
      .options(MacroBuilders::keyOption)
      .options(MacroBuilders::nameOption)
      .processor(data -> {
        if (!data.hasOption("key") && !data.hasOption("name")) {
          // jopt doesn't seem to allow options to depend on each other
          throw new CommandExecuteException("Missing required option(s) [k/key], [n/name]");
        }
        
        if (data.hasOption("key")) {
          // remove by key
          final int key = Keyboard.getKeyIndex(data.getOptionAsString("key").toUpperCase());
          MACROS
            .stream()
            .filter(macro -> macro.getKey() == key)
            .peek(
              __ ->
                Helper.printMessage(
                  "Removing bind for key \"%s\"", Keyboard.getKeyName(key)))
            .forEach(this::removeMacro);
        }
        if (data.hasOption("name")) {
          // remove by name
          final String name = data.getOptionAsString("name");
          MACROS
            .stream()
            .filter(macro -> macro.getName().equals(name))
            .peek(__ -> Helper.printMessage("Removing bind \"%s\"", name))
            .forEach(this::removeMacro);
        }
      })
      .build();
    
    MACROS
      .builders()
      .newCommandBuilder()
      .name("list")
      .description("List all the macros")
      .options(MacroBuilders::fullOption)
      .processor(data -> {
        Helper.printMessage("Macros (%d):", MACROS.size());
        for (MacroEntry macro : MACROS) {
          data.write("\"" + macro.name + "\" : " + Keyboard
              .getKeyName(macro.key));
          if (data.hasOption("full")) {
            data.incrementIndent();
            data.write(this.rawMacroString(macro));
            data.decrementIndent();
          }
        }
      })
      .build();
  }
  
  // TODO: split into separate lines if too long
  private String rawMacroString(MacroEntry macro) {
    return macro.commands.stream()
      .map(nested ->
        nested.stream()
          .collect(Collectors.joining(";", "(", ")"))
      )
      .collect(Collectors.joining(" "));
  }
  
  @Override
  public void onUnload() {
    // MACROS.serializeAll();
  }
  
  @RegisterCommand
  public Command executeMacro(CommandBuilders builder) {
    return builder
      .newCommandBuilder()
      .name("exec")
      .description("Execute a named macro")
      .requiredArgs(1)
      .processor(
        data -> {
          final String name = data.getArgumentAsString(0);
          final MacroEntry macro =
            MACROS
              .stream()
              .filter(entry -> entry.getName().equals(name))
              .findFirst()
              .orElseThrow(
                () -> new CommandExecuteException(String.format("Unknown macro: \"%s\"", name)));
          
          executeMacro(macro);
        })
      .build();
  }
  
  private void executeMacro(MacroEntry macro) {
    final int currentIndex = Optional.ofNullable(macroIndex.putIfAbsent(macro, 0)).orElse(0);
    macro.getCommands().get(currentIndex).forEach(ChatCommandService::handleCommand);
    macroIndex.replace(macro, rotate(currentIndex, 0, macro.getCommands().size() - 1));
  }
  
  private int rotate(int i, int min, int max) {
    return (i >= max) ? min : i + 1;
  }
  
  @RegisterCommand
  public Command bindMacro(CommandBuilders builder) {
    return builder
      .newCommandBuilder()
      .name("bindmacro")
      .description("Usage: .bindmacro f \"clip 5; <command>; ...\" --name cool_macro")
      .options(MacroBuilders::nameOption)
      .processor(MacroBuilders::parseName)
      .processor(
        data -> {
          data.requiredArguments(2);
          final int key = Keyboard.getKeyIndex(data.getArgumentAsString(0).toUpperCase());
          final String name;
          if (data.getOption("name") == null && key == Keyboard.KEY_NONE) {
            throw new CommandExecuteException("A macro must have a name and/or a valid key");
          }
          if (data.getOption("name") == null) name = Keyboard.getKeyName(key);
          else name = (String) data.getOption("name");
          
          final List<ImmutableList<String>> commands =
            data.arguments()
              .stream()
              .skip(1) // skip key
              .map(Object::toString)
              .map(this::parseCommand)
              .collect(Collectors.toList());
          
          final MacroEntry macro =
            new MacroEntry(name, key, commands);
          MACROS
            .stream()
            .filter(m -> m.getName().equals(macro.getName()))
            .findFirst()
            .ifPresent(alreadyExists -> {
              throw new CommandExecuteException(
                String.format("Command \"%s\" already exists!", alreadyExists.getName()));
            });
          MACROS.add(macro);
          
          macro.registerBind();
          
          Helper.printMessage("Successfully bound to %s", Keyboard.getKeyName(key));
        })
      .build();
  }
  
  private ImmutableList<String> parseCommand(String input) {
    return ImmutableList.copyOf(Arrays.asList(
      input.split(";"))); // TODO: don't split semicolons in quotes and allow escaped semicolons
  }
  
  public static class MacroEntry implements ISerializableJson {
    
    private final String name;
    private int key = Keyboard.KEY_NONE;
    private final List<ImmutableList<String>> commands = new ArrayList<>();
    
    private transient KeyBinding bind;
    
    public MacroEntry(String name) {
      this.name = name;
    }
    
    public MacroEntry(String name, int key, List<ImmutableList<String>> commands) {
      this.name = name;
      this.key = key;
      this.commands.addAll(commands);
    }
    
    public int getKey() {
      return Optional.ofNullable(bind).map(KeyBinding::getKeyCode).orElse(this.key);
    }
    
    public String getName() {
      return this.name;
    }
    
    public List<ImmutableList<String>> getCommands() {
      return commands;
    }
    
    public KeyBinding getBind() {
      return this.bind;
    }
    
    // only done for named macros
    private void registerBind() {
      KeyBinding bind = new KeyBinding(name, this.getKey(), "Macros");
      ClientRegistry.registerKeyBinding(bind); // TODO: listen for key pressed for anonymous macros
      this.bind = bind;
    }
    
    @Override
    public void serialize(JsonObject in) {
      JsonObject add = new JsonObject();

      add.addProperty("key", getKey());
      
      JsonArray arr = new JsonArray();
      for (final List<String> list : commands) {
        JsonArray comms = new JsonArray();
        for (final String cmd : list) {
          comms.add(cmd);
        }
        arr.add(comms);
      }
      add.add("commands", arr);

      in.add(name, add);
    }
    
    @Override
    public void deserialize(JsonObject in) {
      JsonObject from = in.getAsJsonObject(name);
      if (from == null) return;

      if (from.get("key") != null) this.key = from.get("key").getAsInt();
      
      if (from.get("commands") != null)
        Streams.stream(from.get("commands").getAsJsonArray())
          .map(JsonElement::getAsJsonArray)
          .map(jArray -> ImmutableList.copyOf(stringIterator(jArray)))
          .forEach(commands::add);
      
      if (key != Keyboard.KEY_NONE)
        this.registerBind();
    }
    
    @Override
    public String toString() {
      return name;
    }
  }
  
  private static Iterator<String> stringIterator(JsonArray jsonArray) {
    return Streams.stream(jsonArray).map(JsonElement::getAsString).iterator();
  }
  
  private static class MacroBuilders {
    
    static void nameOption(OptionParser parser) {
      parser.acceptsAll(Arrays.asList("name", "n"), "name").withRequiredArg();
    }
    
    static void keyOption(OptionParser parser) {
      parser.acceptsAll(Arrays.asList("key", "k"), "key").withRequiredArg();
    }
    
    static void fullOption(OptionParser parser) {
      parser.accepts("full").withOptionalArg();
    }
    
    static void parseName(ExecuteData data) {
      final @Nullable String name = (String) data.getOption("name");
      data.set("name", Optional.ofNullable(name));
    }
  }
}
