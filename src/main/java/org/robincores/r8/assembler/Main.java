package org.robincores.r8.assembler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import com.google.gson.Gson;

public class Main {
  public static void main(String[] args) {
    if (args.length < 2) {
      System.out.println("Usage: AssemblerMain <file.asm> <output.bin>");
      System.exit(1);
    }

    // Load the configuration from r8.json in resources
    System.out.println("Loading configuration...");
    AssemblerSpec spec = loadConfigFromResources("/org/robincores/r8/assembler/R824.json");
    if (spec == null) {
      System.err.println("Failed to load assembler configuration.");
      System.exit(1);
    }
    System.out.println("Configuration loaded successfully.");

    Assembler assembler = new Assembler(spec);

    // Load the assembly file
    String asmFilename = args[0];
    System.out.println("Reading assembly file: " + asmFilename);
    String asmText;
    try {
      asmText = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(asmFilename)), StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.err.println("Error reading assembly file: " + e.getMessage());
      return;
    }
    System.out.println("Assembly file read successfully.");

    // Assemble the file
    System.out.println("Assembling the file...");
    AssemblerState state = assembler.assembleFile(asmText);

    // Report errors, if any
    if (!state.errors.isEmpty()) {
      for (AssemblerError error : state.errors) {
        System.out.println(asmFilename + "(" + error.line + "): " + error.msg);
      }
      System.exit(2);
    }
    System.out.println("Assembly completed successfully.");

    // Write the output (machine code) to the output file
    String outputFilename = args[1];
    System.out.println("Writing output to: " + outputFilename);
    try (FileOutputStream fos = new FileOutputStream(outputFilename)) {
      for (Integer word : state.output) {
        fos.write(word.byteValue());
      }
      System.out.println("Assembly successful. Output written to " + outputFilename);
    } catch (IOException e) {
      System.err.println("Error writing output file: " + e.getMessage());
    }
  }

  // Method to load configuration from resources (r8.json)
  public static AssemblerSpec loadConfigFromResources(String resourcePath) {
    InputStream inputStream = Assembler.class.getResourceAsStream(resourcePath);
    if (inputStream == null) {
      System.err.println("Configuration file not found: " + resourcePath);
      return null;
    }

    try (Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8.name())) {
      String jsonText = scanner.useDelimiter("\\A").next(); // Read the entire file
      Gson gson = new Gson();
      return gson.fromJson(jsonText, AssemblerSpec.class); // Parse JSON into AssemblerSpec
    } catch (Exception e) {
      System.err.println("Error parsing configuration: " + e.getMessage());
      return null;
    }
  }
}

