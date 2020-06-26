"use strict";

// original: https://github.com/snabbdom/snabbdom/blob/master/src/modules/props.ts

function updateProps(oldVnode, vnode) {
    var key, cur, old, elm = vnode.elm, oldProps = oldVnode.data.props, props = vnode.data.props;

    if (!oldProps && !props) return;
    if (oldProps === props) return;
    oldProps = oldProps || {};
    props = props || {};

    for (key in oldProps) {
        if (!props[key]) {
            delete elm[key];
        }
    }

    for (key in props) {
        cur = props[key];
        old = elm[key];
        if (old !== cur) {
            elm[key] = cur;
        }
    }
}

module.exports = {
    default: {
        create: updateProps,
        update: updateProps
    }
};
