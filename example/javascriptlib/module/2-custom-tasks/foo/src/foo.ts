(function () {
    try {
        const Bar = require(process.env.BAR_MODULE_PATH + "/bar.ts").default;
        console.log(`Bar.value: ${Bar.value}`);
    } catch (error) {
        console.log("Bar was not found");
    }

    const lineCount = process.env.LINE_COUNT;
    const args = process.argv.slice(2);
    console.log(`text: ${args.join(", ")}`);
    console.log(`Line count: ${lineCount}`);
})();