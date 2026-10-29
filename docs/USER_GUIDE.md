# Forest Point Collector: user guide

This guide explains how to set up the layers folder and create point and line layers.
It was written from the app's screen labels and messages. Check it on your phone and
tell us what differs.

## 1. Install the app
Install the APK for your Android version (see the Releases page). Allow "Install unknown apps"
for the app you open the APK with.

## 2. Set up the layers folder
The app reads its shapefiles from a folder on the phone's internal storage.

1. Open the app and go to **Settings**.
2. In the **Layers folder** row, tap **Change…** and choose a folder on internal storage.
   Pick a folder, not a single file.
3. The folder should contain a subfolder named **GPS_MAP**. If you see the message
   "is GPS_MAP present in the picked folder?", create that subfolder inside the folder you picked
   and try again.
4. Put your layers in that folder. Each layer is a set of files with the same name:
   `.shp`, `.shx`, `.dbf` and `.prj` (and `.cpg` if you have it).

## 3. Create a new empty layer
1. In **Settings**, tap **Create…** in the **Create new layer** row.
2. Enter a **Layer name** and choose the **Coordinate system** (for example UTM Zone 10N).
3. Fill in the attribute fields: **Field 1** and **Field 2** (text, up to 15 characters).
4. Save. The app writes the `.shp`, `.shx`, `.dbf`, `.prj` and `.cpg` files into the layers folder.

## 4. Check the layers
In **Settings**, tap **Verify…** in the **Verify layers** row. It checks that each `.shp` has its
sidecar files, a readable header, a known coordinate system, and matches the project.

## 5. Use the layers on the map
1. Go back to the main screen and tap **Start_MAP**. The map loads the layers from the folder.
2. If you changed the layers folder, tap **Close_MAP**, then **Start_MAP** again. The app
   asks you to do this after a change.
3. Choose the layer to edit in the **Editable layer** setting, then tap **Start Editing**.
   Tap the map to add a point, and **Save Edits** to write them to the layer.
4. **MOB** saves a point with the current GPS position into `POINTS.shp`.
   Start_MAP must have been run first, and the app says "No GPS fix yet" until it has a position.

## 6. Settings that matter
- **Default Coordinate System:** Auto (first layer), UTM Zone, or WGS84 (auto zone).
- **Save Edits every … seconds:** autosave interval (blank = no autosave). Default 40.
- **Add Point layer:** default target for Add_Point (default `XGV_MP.shp`).
- **MOB point collection layer:** fixed to `POINTS.shp`.
- **Track line collection layer:** target for recorded tracks (default `track.shp`).
- **Save Map Settings / Save Map As:** saves the settings into a named map file. Default name is "mirshp".
- **Restore default settings:** resets the settings to the shipped defaults and keeps the layer list.
