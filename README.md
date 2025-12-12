# MF_Bluemap

A bridge plugin that integrates **MedievalFactions** with **BlueMap** to display faction claims on your web map.

## Features
- **Visual Claims**: Shows MedievalFactions claims on BlueMap.
- **Dynamic Colors**: Automatically generates consistent colors for factions based on their ID (if no specific color is configured).
- **Customizable**: Configure transparency, line width, label format, and specific faction colors in `config.yml`.
- **Standalone**: Does not require RIVCore or other massive libraries.

## Dependencies & Compilation

To compile this project, you need to provide the **MedievalFactions** dependency, as it is not hosted in a public Maven repository.

### Setup Instructions
1.  Create a folder named `libs` in the project root.
2.  Place your `MedievalFactions` JAR file inside the `libs` folder.
3.  Rename the jar to: `medieval-factions-5.6.0-all.jar` (or update `pom.xml` to match your filename).
4.  Run the build command:
    ```bash
    mvn clean package
    ```

### Dependencies
- **Java 17** or higher.
- **BlueMap API** (Automatically downloaded via Maven).
- **MedievalFactions** (Must be provided manually in `libs/`).
