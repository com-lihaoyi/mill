## Steps to Build the Android App

1. **Create Project Folder:**
   - Create and navigate to the root project folder:
     ```bash
     mkdir hello-world && cd hello-world
     ```

2. **Create Required Files:**
   - Create the following structure using mkdir:
     ```
     ./AndroidManifest.xml
     ./java/com/helloworld/app/MainActivity.java
     ./res/layout/activity_main.xml
     ./res/values/styles.xml
     ```

3. **Set Up Android SDK Path:**
   - Add the Android SDK tools to your `PATH`:
     ```bash
     export PATH="path_to_jdk:$PATH"
     ```

4. **Create Temporary Build Folder:**
   - Create a folder for build files:
     ```bash
     mkdir __build
     ```

5. **Generate R.java:**
   - Run the command to generate resource IDs:
     ```bash
     aapt package -f -m -J __build/gen -S res -M AndroidManifest.xml -I "path_to_jdk/android.jar"
     ```

6. **Compile Java Code:**
   - Compile the Java files:
     ```bash
     javac -classpath "path_to_jdk/android.jar" -d "__build/obj" "__build/gen/com/helloworld/app/R.java" java/com/helloworld/app/MainActivity.java
     ```

7. **Convert to DEX Format:**
   - Create a JAR file and convert classes:
     ```bash
     d8 __build/obj/**/*.class --output __build/apk/my_classes.jar --no-desugaring
     ```

   - Merge with `android.jar` to create `classes.dex`:
     ```bash
     pushd __build/apk
     d8 "path_to_jdk/android.jar" my_classes.jar
     popd
     ```

8. **Create Unsigned APK:**
   - Package the app into an unsigned APK:
     ```bash
     aapt package -f -M AndroidManifest.xml -S res -I "path_to_jdk/android.jar" -F __build/helloworld.unsigned.apk __build/apk/
     ```

9. **Align the APK:**
   - Align the APK:
     ```bash
     zipalign -f -p 4 __build/helloworld.unsigned.apk __build/helloworld.aligned.apk
     ```

10. **Generate a Keystore (if needed):**
    - Create a keystore:
      ```bash
      keytool -genkeypair -keystore keystore.jks -alias androidkey -dname "CN=helloworld, OU=ID, O=helloworld, L=Abc, S=Xyz, C=IN" -validity 10000 -keyalg RSA -keysize 2048 -storepass android -keypass android
      ```

11. **Sign the APK:**
    - Sign the aligned APK:
      ```bash
      apksigner sign --ks keystore.jks --ks-key-alias androidkey --ks-pass pass:android --key-pass pass:android --out __build/helloworld.apk __build/helloworld.aligned.apk
      ```

12. **Final APK:**
    - The final APK `helloworld.apk` is ready for installation on a device or emulator.

---