package dev.customagent.plugins;
import dev.customagent.plugin.ProcessingPlugin;
import java.util.Map;
public class UppercasePlugin implements ProcessingPlugin {
  public String id(){return "uppercase";} public String name(){return "Uppercase";} public String description(){return "Converts input text to uppercase.";}
  public String process(String input, Map<String,String> configuration){return input.toUpperCase();}
}
