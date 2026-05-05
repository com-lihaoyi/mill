package foo

import groovy.xml.MarkupBuilder
import groovy.cli.commons.CliBuilder

class Foo {
    static String generateHtml(String text) {
        def writer = new StringWriter()
        new MarkupBuilder(writer).h1 {
            mkp.yield text
        }
        writer.toString()
    }

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
        println generateHtml(textToProcess)
    }
}
