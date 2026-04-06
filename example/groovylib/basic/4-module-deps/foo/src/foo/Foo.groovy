package foo

import bar.Bar
import groovy.cli.commons.CliBuilder

class Foo {

    static void main(String[] args) {
        def cli = new CliBuilder(usage:'help')
        cli.t(longOpt:'text', args: 1, 'Passes text to the HTML generation')
        def options = cli.parse(args)

        if (!options) {
            return
        }

        if (options.h) {
            cli.usage()
            return
        }

        String textToProcess = options.t ?: "hello from main"
        println Bar.generateHtml(textToProcess)
    }
}
