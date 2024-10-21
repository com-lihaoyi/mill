def htmlContent = "<html><body><h1>Hello!</h1><p>" + args[0] + "</p></body></html>"

def outputFile = new File(args[1])
outputFile.write(htmlContent)