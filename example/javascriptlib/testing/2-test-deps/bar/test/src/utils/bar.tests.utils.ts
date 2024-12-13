function compareObjects(objA, objB) {
    const keysA = Object.keys(objA);
    return keysA.every(key => key in objB && objA[key] === objB[key]);
}

export function compareObject(x: any, y: any): Boolean {
    return compareObjects(x, y)
}

export function compare(x: any, y: any): Boolean {
    return x === y
}