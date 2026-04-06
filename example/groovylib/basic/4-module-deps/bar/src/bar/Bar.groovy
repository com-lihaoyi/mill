package bar

import groovy.xml.MarkupBuilder

class Bar {
    static String generateHtml(String text) {
        def writer = new StringWriter()
        new MarkupBuilder(writer).h1 {
            mkp.yield text
        }
        writer.toString()
    }
}
