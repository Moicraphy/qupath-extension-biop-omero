# QuPath OMERO extension

Welcome to the OMERO extension for [QuPath](http://qupath.github.io)!

This adds support for accessing images hosted on an [OMERO](https://www.openmicroscopy.org/omero/) 
server through OMERO gateway API.

The extension is intended for the (at the time of writing) not-yet-released 
QuPath v0.3.
It is not compatible with earlier QuPath versions.

## Installing

To install the OMERO extension, download the latest `qupath-extension-biop-omero-[version].jar` file from [releases](https://github.com/qupath/qupath-extension-biop-omero/releases) and drag it onto the main QuPath window.

If you haven't installed any extensions before, you'll be prompted to select a QuPath user directory.
The extension will then be copied to a location inside that directory.

The `OMERO-java dependencies` are required to make this extension working. Download the zipped file from the [OMERO download page](https://www.openmicroscopy.org/omero/downloads/), under "OMERO Java". Unzip the file and copy `libs` folder in `C:\QuPath_Common_Data_0.3\extensions`.

You might then need to restart QuPath (but not your computer).


## Building

You can build the extension using OpenJDK 11 or later with

```bash
gradlew clean build
```

The output will be under `build/libs`.
You can drag the jar file on top of QuPath to install the extension.

## Documentation
You can find all the documentation on how to use this extension on our [wiki page](https://wiki-biop.epfl.ch/en/Image_Storage/OMERO/OmeroQuPath).
