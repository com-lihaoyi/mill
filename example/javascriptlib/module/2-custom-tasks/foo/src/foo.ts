import Bar from "@generated/bar";

(function () {
    console.log(`Bar.value: ${Bar.value}`);
    const lineCount = process.env.LINE_COUNT;
    const args = process.argv.slice(2);
    console.log(`text: ${args.join(", ")}`);
    console.log(`Line count: ${lineCount}`);
})();