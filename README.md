# Auto Pokémon Catch Classifier (Aliucord Plugin)

This is an on-device Kotlin plugin project for **Aliucord** (`Aliucord/plugins-template`), bundling your ONNX models (`model.onnx` + `kratos_model.onnx`) and Pokétwo class/alias tables directly inside the plugin—no external server required.

## How to Build the `.zip` Using GitHub Actions (No PC Required)
1. Go to **https://github.com/Aliucord/plugins-template**, click **Use this template → Create a new repository**, and check **Include all branches**.
2. Extract this zip and copy the `plugins/AutoPokemonCatchClassifier/` folder into `plugins/` in your new repository.
3. Commit and push to `main`. GitHub Actions will automatically compile `AutoPokemonCatchClassifier.zip` and push it to your repo's `builds` branch.
4. Download `AutoPokemonCatchClassifier.zip` from the `builds` branch and place it into `/sdcard/Aliucord/plugins/` on your phone, then restart Aliucord.

## Or Build Locally
In Android Studio (or CLI with JDK 21 + Android SDK):
```bash
./gradlew :AutoPokemonCatchClassifier:make
```
