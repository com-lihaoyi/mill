# Build Server Protocol support for mill 

The contrib.bsp module was created in order to integrate the Mill build tool
with IntelliJ IDEA via the Build Server Protocol (BSP). It implements most of
the server side functionality described in BSP, and can therefore connect to a 
BSP client, including the one behind IntelliJ IDEA. This allows a lot of mill
tasks to be executed from the IDE.

# Importing an existing mill project in IntelliJ via BSP

1) Clone the mill git repo
2) Publish your mill version locally with `ci/publish-contrib`
3) Run the following command in the working directory of your project:
   
                   `~/mill-release -i mill.contrib.BSP/install`
                   
    This should create a `.bsp/` directory inside your working directory,
    containing a BSP connection file that clients can use to start the
    BSP server for Mill.
    
    This command should be ran whenever you change the version of mill that
    you use.
    
   4) Now you can use IntelliJ to import your project from existing sources
   via bsp ( currently available in the nightly release ). Note: It might
   take a few minutes to import a project the very first time.
   
After the bsp support module would be published, it should be enough to:

1) Install mill
2) Add the following import statement in the build.sc of your project:

        `import $ivy.com.lihaoyi::mill-contrib-bsp:$OFFICIAL_MILL_VERSION`

3) Run the following command in the working directory of your project:

                      `mill -i mill.contrib.BSP/install`

## Known Issues:

- Sometimes build from IntelliJ might fail due to a NoClassDefFoundException
being thrown during the evaluation of tasks, a bug not easy to reproduce.
In this case it is recommended to refresh the bsp project.
