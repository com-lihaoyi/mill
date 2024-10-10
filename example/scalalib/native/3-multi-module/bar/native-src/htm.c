#include "htmc.h"

#ifndef HTMC_C
#define HTMC_C

#include <stdarg.h>

const char *htmc_tags[] = {
"a", "abbr", "address", "area", "article", "aside", "audio", "b", "base", "bdi", "bdo", "blockquote", "body", "br", "button", "canvas", "caption", "cite", "code", "col", "colgroup", "data", "datalist", "dd", "del", "details", "dfn", "dialog", "div", "dl", "dt", "em", "embed", "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hgroup", "hr", "html", "i", "iframe", "img", "input", "ins", "kbd", "label", "legend", "li", "link", "main", "map", "mark", "math", "menu", "meta", "meter", "nav", "noscript", "object", "ol", "optgroup", "option", "output", "p", "param", "picture", "pre", "progress", "q", "rp", "rt", "ruby", "s", "samp", "script", "section", "select", "slot", "small", "source", "span", "strong", "style", "sub", "summary", "sup", "svg", "table", "tbody", "td", "template", "textarea", "tfoot", "th", "thead", "time", "title", "tr", "track", "u", "ul", "var", "video", "wbr"
};

const uint32_t htmc_tag_lengths[] = {
sizeof("a") - 1, sizeof("abbr") - 1, sizeof("address") - 1, sizeof("area") - 1, sizeof("article") - 1, sizeof("aside") - 1, sizeof("audio") - 1, sizeof("b") - 1, sizeof("base") - 1, sizeof("bdi") - 1, sizeof("bdo") - 1, sizeof("blockquote") - 1, sizeof("body") - 1, sizeof("br") - 1, sizeof("button") - 1, sizeof("canvas") - 1, sizeof("caption") - 1, sizeof("cite") - 1, sizeof("code") - 1, sizeof("col") - 1, sizeof("colgroup") - 1, sizeof("data") - 1, sizeof("datalist") - 1, sizeof("dd") - 1, sizeof("del") - 1, sizeof("details") - 1, sizeof("dfn") - 1, sizeof("dialog") - 1, sizeof("div") - 1, sizeof("dl") - 1, sizeof("dt") - 1, sizeof("em") - 1, sizeof("embed") - 1, sizeof("fieldset") - 1, sizeof("figcaption") - 1, sizeof("figure") - 1, sizeof("footer") - 1, sizeof("form") - 1, sizeof("h1") - 1, sizeof("h2") - 1, sizeof("h3") - 1, sizeof("h4") - 1, sizeof("h5") - 1, sizeof("h6") - 1, sizeof("head") - 1, sizeof("header") - 1, sizeof("hgroup") - 1, sizeof("hr") - 1, sizeof("html") - 1, sizeof("i") - 1, sizeof("iframe") - 1, sizeof("img") - 1, sizeof("input") - 1, sizeof("ins") - 1, sizeof("kbd") - 1, sizeof("label") - 1, sizeof("legend") - 1, sizeof("li") - 1, sizeof("link") - 1, sizeof("main") - 1, sizeof("map") - 1, sizeof("mark") - 1, sizeof("math") - 1, sizeof("menu") - 1, sizeof("meta") - 1, sizeof("meter") - 1, sizeof("nav") - 1, sizeof("noscript") - 1, sizeof("object") - 1, sizeof("ol") - 1, sizeof("optgroup") - 1, sizeof("option") - 1, sizeof("output") - 1, sizeof("p") - 1, sizeof("param") - 1, sizeof("picture") - 1, sizeof("pre") - 1, sizeof("progress") - 1, sizeof("q") - 1, sizeof("rp") - 1, sizeof("rt") - 1, sizeof("ruby") - 1, sizeof("s") - 1, sizeof("samp") - 1, sizeof("script") - 1, sizeof("section") - 1, sizeof("select") - 1, sizeof("slot") - 1, sizeof("small") - 1, sizeof("source") - 1, sizeof("span") - 1, sizeof("strong") - 1, sizeof("style") - 1, sizeof("sub") - 1, sizeof("summary") - 1, sizeof("sup") - 1, sizeof("svg") - 1, sizeof("table") - 1, sizeof("tbody") - 1, sizeof("td") - 1, sizeof("template") - 1, sizeof("textarea") - 1, sizeof("tfoot") - 1, sizeof("th") - 1, sizeof("thead") - 1, sizeof("time") - 1, sizeof("title") - 1, sizeof("tr") - 1, sizeof("track") - 1, sizeof("u") - 1, sizeof("ul") - 1, sizeof("var") - 1, sizeof("video") - 1, sizeof("wbr") - 1
};

void htmc_cleanup_unused_buffers(HtmcAllocations *ha, size_t used_idx)
{
    size_t i = 0;
    for( ; i != used_idx ; i++)
    {
        free(ha->buffers[i]);
    }
    for(i += 1; i < ha->nb ; i++)
    {
        free(ha->buffers[i]);
    }
    
    free(ha->caps);
    free(ha->sizes);
    free(ha->unused);
    free(ha->buffers);
}

size_t htmc_find_buffer(const HtmcAllocations *ha, const char *buffer)
{
    size_t i;
    for(i = 0; i < ha->nb ; i++)
    {
        if(buffer == ha->buffers[i])
            break;
    }
    return i;
}

void htmc_grow_buffers(HtmcAllocations *ha)
{
    ha->buffers = realloc(ha->buffers, ha->nb * 2 * sizeof(char*));
    memset(ha->buffers + ha->nb, 0, ha->nb * sizeof(char*));
    
    ha->caps = realloc(ha->caps, ha->nb * 2 * sizeof(size_t));
    memset(ha->caps + ha->nb, 0, ha->nb * sizeof(size_t));
    
    ha->sizes = realloc(ha->sizes, ha->nb * 2 * sizeof(size_t));
    memset(ha->sizes + ha->nb, 0, ha->nb * sizeof(size_t));
    
    ha->unused = realloc(ha->unused, ha->nb * 2 * sizeof(bool));
    memset(ha->unused + ha->nb, 1, ha->nb * sizeof(bool));
    
    ha->nb *= 2;
}

void htmc_set_unused(HtmcAllocations *ha, const char *str)
{
    size_t idx = htmc_find_buffer(ha, str);
    ha->unused[idx] = true;
}

void htmc_set_unused_if_alloced(HtmcAllocations *ha, const char *str)
{
    size_t found = htmc_find_buffer(ha, str);
    if(found != ha->nb)
    {
        ha->unused[found] = true;
    }
}

size_t htmc_find_unused(const HtmcAllocations *ha)
{
    size_t first_unused;
    
    for(first_unused = 0 ; first_unused < ha->nb ; first_unused++)
    {
        if(ha->unused[first_unused])
        {
            goto find_unused_and_alloced;
        }
    }
    
    // no unused buffers were found
    return first_unused;
    
    find_unused_and_alloced:
    for(size_t i = first_unused ; i < ha->nb ; i++)
    {
        if(ha->unused[i] && ha->caps[i] != 0)
        {
            return i;
        }
    }
    
    return first_unused;
}

size_t htmc_get_unused(HtmcAllocations *ha, size_t with_cap)
{
    size_t unused_buffer_idx = htmc_find_unused(ha);
    if(unused_buffer_idx == ha->nb)
    {
        unused_buffer_idx = ha->nb;
        htmc_grow_buffers(ha);
        
        char **unused_buffer = &ha->buffers[ unused_buffer_idx ];
        *unused_buffer = calloc(with_cap, sizeof(char));
        ha->caps[ unused_buffer_idx ] = with_cap;
    }
    else if(with_cap > ha->caps[ unused_buffer_idx ])
    {
        char **unused_buffer = &ha->buffers[ unused_buffer_idx ];
        *unused_buffer = realloc(*unused_buffer, with_cap);
        ha->caps[ unused_buffer_idx ] = with_cap;
    }
    
    ha->unused[ unused_buffer_idx ] = false;
    
    return unused_buffer_idx;
}

size_t htmc_concat_strings_into(HtmcAllocations *ha, HtmcStrsArr strs, size_t unused_buffer_idx)
{
    char **unused_buffer = &ha->buffers[ unused_buffer_idx ];
    
    size_t *cap = &ha->caps[ unused_buffer_idx ];
    size_t *size = &ha->sizes[ unused_buffer_idx ];
    *size = 0;
    
    // if no strings were provided, return an empty string
    if(strs.nb == 0)
    {
        if(*cap == 0)
        {
            *unused_buffer = calloc(1, sizeof(char));
        }
        **unused_buffer = '\0';
        return unused_buffer_idx;
    }
    
    for(size_t i = 0 ; i < strs.nb ; i++)
    {
        char *next_str = strs.arr[i];
        size_t next_len = strlen(next_str);
        if(*size + next_len >= *cap)
        {
            *cap = (next_len + *cap) * 2;
            *unused_buffer = realloc(*unused_buffer, *cap);
        }
        memcpy(*unused_buffer + *size, next_str, next_len);
        
        htmc_set_unused_if_alloced(ha, next_str);
        
        *size = *size + next_len;
    }
    
    (*unused_buffer)[*size] = '\0';
    return unused_buffer_idx;
}

size_t htmc_concat_strings(HtmcAllocations *ha, HtmcStrsArr strs)
{
    size_t unused_buffer_idx = htmc_get_unused(ha, 16);
    return htmc_concat_strings_into(ha, strs, unused_buffer_idx);
}

void htmc_append_to_buffer_idx(HtmcAllocations *ha, size_t append_to, HtmcStrsArr strs)
{
    char **append_to_str = &ha->buffers[ append_to ];
    size_t *len = &ha->sizes[ append_to ];
    size_t *cap = &ha->caps [ append_to ];
    
    for(size_t i = 0 ; i < strs.nb ; i++)
    {
        char *next_str = strs.arr[i];
        size_t next_len = strlen(strs.arr[i]);
        bool is_copy = (next_str == *append_to_str);
        
        if(*len + next_len >= *cap)
        {
            htmc_gurantee_cap(append_to_str, cap, (next_len + *cap) * 2);
        }
        
        // if it's a copy of *append_to_str that means it might have been invalidate with the htmc_gurantee_cap
        if(is_copy)
        {
            memmove(*append_to_str + *len, *append_to_str, next_len);
            *len += next_len;
        }
        else
        {
            memmove(*append_to_str + *len, next_str, next_len);
            *len += next_len;
            
            htmc_set_unused_if_alloced(ha, next_str);
        }
    }
    
    (*append_to_str)[*len] = '\0';
}

char *htmc_surround_by_tag(HtmcAllocations *ha, uint16_t tag_id, size_t str_idx)
{
    const size_t between_len = ha->sizes[ str_idx ];
    char **between_ptr = &ha->buffers[ str_idx ];
    size_t *cap = &ha->caps[ str_idx ];
    const size_t tag_len = htmc_tag_lengths[ tag_id ];
    const size_t needed_cap = 1 + tag_len + 1 + between_len + 1 + 1 + tag_len + 1 + 1;
    const char *tag = htmc_tags[ tag_id ];
    
    htmc_gurantee_cap(between_ptr, cap, needed_cap);
    
    memmove(*between_ptr + 1 + tag_len + 1, *between_ptr, between_len);
    
    memcpy(*between_ptr, "<", 1);
    memcpy(*between_ptr + 1, tag, tag_len);
    memcpy(*between_ptr + 1 + tag_len, ">", 1);
    memcpy(*between_ptr + 1 + tag_len + 1 + between_len, "</", 2);
    memcpy(*between_ptr + 1 + tag_len + 1 + between_len + 1 + 1, tag, tag_len);
    memcpy(*between_ptr + 1 + tag_len + 1 + between_len + 1 + 1 + tag_len, ">", 1);
    (*between_ptr)[ needed_cap - 1 ] = '\0';
    
    ha->sizes[ str_idx ] = needed_cap - 1;
    
    return *between_ptr;
}

char *htmc_surround_by_tag_with_attrs(HtmcAllocations *ha, uint16_t tag_id, HtmcStrsArr attrs, size_t str_idx)
{
    const size_t between_len = ha->sizes[ str_idx ];
    const size_t tag_len = htmc_tag_lengths[ tag_id ];
    const size_t needed_cap = 1 + tag_len + 1 + between_len + 1 + 1 + tag_len + 1 + 1;
    const char *tag = htmc_tags[ tag_id ];
    
    size_t unused_buffer_idx = htmc_get_unused(ha, needed_cap);
    const char *between = ha->buffers[ str_idx ];
    char **unused_buffer = &ha->buffers[ unused_buffer_idx ];
    
    size_t *cap = &ha->caps[ unused_buffer_idx ];
    size_t size = 0;
    
    memcpy(*unused_buffer, "<", 1);
    size += 1;
    
    memcpy(*unused_buffer + size, tag, tag_len);
    size += tag_len;
    
    // insert attributes here:
    for(size_t i = 0 ; i < attrs.nb ; i++)
    {
        size_t attr_len = strlen(attrs.arr[i]);
        if(*cap <= size + attr_len + 1) // 1 for the spaces between each attribute
        {
            *unused_buffer = realloc(*unused_buffer, size + (attr_len * 2));
            *cap = size + (attr_len * 2);
        }
        memcpy(*unused_buffer + size, " ", 1);
        size += 1;
        memcpy(*unused_buffer + size, attrs.arr[i], attr_len);
        size += attr_len;
    }
    
    if(*cap <= size + 1 + between_len + 2 + tag_len + 1)
    {
        *unused_buffer = realloc(*unused_buffer, size + 1 + between_len + 2 + tag_len + 1 + 1);
        *cap = size + 1 + between_len + 2 + tag_len + 1 + 1;
    }
    
    memcpy(*unused_buffer + size, ">", 1);
    size += 1;
    
    memcpy(*unused_buffer + size, between, between_len);
    size += between_len;
    
    memcpy(*unused_buffer + size, "</", 2);
    size += 2;
    
    memcpy(*unused_buffer + size, tag, tag_len);
    size += tag_len;
    
    memcpy(*unused_buffer + size, ">", 1);
    size += 1;
    
    (*unused_buffer)[size] = '\0';
    
    ha->sizes[ unused_buffer_idx ] = size;
    ha->unused[ str_idx ] = true;
    
    return *unused_buffer;
}

char *htmc_make_tag(HtmcAllocations *ha, uint16_t tag_id)
{
    const char *tag = htmc_tags[ tag_id ];
    const size_t tag_len = htmc_tag_lengths[ tag_id ];
    
    size_t unused_buffer_idx = htmc_get_unused(ha, 1 + tag_len + 1 + 1);
    char **unused_buffer = &ha->buffers[ unused_buffer_idx ];
    
    memcpy(*unused_buffer, "<", 1);
    memcpy(*unused_buffer + 1, tag, tag_len);
    memcpy(*unused_buffer + 1 + tag_len, ">", 1);
    (*unused_buffer)[ 1 + tag_len + 1 ] = '\0';
    
    ha->sizes[ unused_buffer_idx ] = tag_len + 2;
    
    return *unused_buffer;
}

char *htmc_make_tag_with_attrs(HtmcAllocations *ha, uint16_t tag_id, HtmcStrsArr attrs, char *dummy)
{
    (void)dummy;
    
    const char *tag = htmc_tags[ tag_id ];
    const size_t tag_len = htmc_tag_lengths[ tag_id ];
    
    size_t unused_buffer_idx = htmc_get_unused(ha, 1 + tag_len + 1 + 1);
    char **unused_buffer = &ha->buffers[ unused_buffer_idx ];
    size_t *size = &ha->sizes[ unused_buffer_idx ];
    *size = 0;
    size_t *cap = &ha->caps[ unused_buffer_idx ];
    
    memcpy(*unused_buffer, "<", 1);
    *size += 1;
    
    memcpy(*unused_buffer + 1, tag, tag_len);
    *size += tag_len;
    
    // insert attributes here:
    for(size_t i = 0 ; i < attrs.nb ; i++)
    {
        size_t attr_len = strlen(attrs.arr[i]);
        if(*size + attr_len + 1 >= *cap)
        {
            *unused_buffer = realloc(*unused_buffer, (*size + attr_len) * 2);
            *cap = (*size + attr_len) * 2;
        }
        
        memcpy(*unused_buffer + *size, " ", 1);
        *size += 1;
        
        memcpy(*unused_buffer + *size, attrs.arr[i], attr_len);
        *size += attr_len;
    }
    
    memcpy(*unused_buffer + *size, ">", 2); // 2 for the '\0'
    *size += 1;
    
    return *unused_buffer;
}

char *htmc_repeat_(HtmcAllocations *ha, uint32_t nb, HtmcStrsArr strs)
{
    size_t combined_str_idx = htmc_concat_strings(ha, strs);
    
    char **combined_str_ptr = &ha->buffers[ combined_str_idx ];
    size_t *cap = &ha->caps[ combined_str_idx ];
    const size_t combined_strlen = ha->sizes[ combined_str_idx ];
    size_t *size = &ha->sizes[ combined_str_idx ];
    
    if(combined_strlen * nb >= *cap)
    {
        *combined_str_ptr = realloc(*combined_str_ptr, combined_strlen * nb + 1);
        *cap = combined_strlen * nb + 1;
    }
    
    for(uint32_t i = 1 ; i < nb ; i++)
    {
        memcpy(*combined_str_ptr + *size, *combined_str_ptr, combined_strlen);
        *size += combined_strlen;
    }
    
    (*combined_str_ptr)[*size] = '\0';
    
    return *combined_str_ptr;
}

// should mod take idx? maybe leave that as the re-entrant version with void*, and let user handle idx
char *htmc_repeat_modify_(HtmcAllocations *ha, uint32_t nb, void(*mod)(const char *before_mod, size_t len, char **buffer, size_t *cap, uint32_t idx), HtmcStrsArr strs)
{
    size_t combined_str_idx = htmc_concat_strings(ha, strs);
    
    size_t iter_copy_idx = htmc_strdup(ha, combined_str_idx);
    
    size_t unused_buffer_idx = htmc_get_unused(ha, ha->sizes[ combined_str_idx ] + 1);
    
    char **combined_str_ptr = &ha->buffers[ combined_str_idx ];
    size_t *cap = &ha->caps[ combined_str_idx ];
    size_t *size = &ha->sizes[ combined_str_idx ];
    
    char **iter_copy_buffer = &ha->buffers[ iter_copy_idx ];
    const char *iter_copy = *iter_copy_buffer;
    const size_t copy_len = *size;
    
    char **unused_buffer = &ha->buffers[ unused_buffer_idx ];
    **unused_buffer = '\0';
    size_t *unused_cap = &ha->caps[ unused_buffer_idx ];
    
    for(uint32_t i = 0 ; i < nb ; i++)
    {
        mod(iter_copy, copy_len, unused_buffer, unused_cap, i);
        size_t modified_len = strlen(*unused_buffer);
        
        if(modified_len + *size >= *cap)
        {
            *combined_str_ptr = realloc(*combined_str_ptr, *size + (modified_len * 2));
            *cap = *size + (modified_len * 2);
        }
        
        memcpy(*combined_str_ptr + *size, *unused_buffer, modified_len);
        
        *size += modified_len;
    }
    
    ha->unused[ iter_copy_idx ] = true;
    ha->unused[ unused_buffer_idx ] = true;
    
    (*combined_str_ptr)[*size] = '\0';
    
    return *combined_str_ptr;
}

char *htmc_repeat_modify_r_(HtmcAllocations *ha, uint32_t nb, void(*mod)(const char *before_mod, size_t len, char **buffer, size_t *cap, uint32_t idx, void *arg), void *arg, HtmcStrsArr strs)
{
    size_t combined_str_idx = htmc_concat_strings(ha, strs);
    
    size_t iter_copy_idx = htmc_strdup(ha, combined_str_idx);
    
    size_t unused_buffer_idx = htmc_get_unused(ha, ha->sizes[ combined_str_idx ] + 1);
    
    char **combined_str_ptr = &ha->buffers[ combined_str_idx ];
    size_t *cap = &ha->caps[ combined_str_idx ];
    size_t *size = &ha->sizes[ combined_str_idx ];
    
    char **iter_copy_buffer = &ha->buffers[ iter_copy_idx ];
    const char *iter_copy = *iter_copy_buffer;
    const size_t copy_len = *size;
    
    char **unused_buffer = &ha->buffers[ unused_buffer_idx ];
    **unused_buffer = '\0';
    size_t *unused_cap = &ha->caps[ unused_buffer_idx ];
    
    for(uint32_t i = 0 ; i < nb ; i++)
    {
        mod(iter_copy, copy_len, unused_buffer, unused_cap, i, arg);
        size_t modified_len = strlen(*unused_buffer);
        
        if(modified_len + *size >= *cap)
        {
            *combined_str_ptr = realloc(*combined_str_ptr, *size + (modified_len * 2));
            *cap = *size + (modified_len * 2);
        }
        
        memcpy(*combined_str_ptr + *size, *unused_buffer, modified_len);
        
        *size += modified_len;
    }
    
    ha->unused[ iter_copy_idx ] = true;
    ha->unused[ unused_buffer_idx ] = true;
    
    (*combined_str_ptr)[*size] = '\0';
    
    return *combined_str_ptr;
}

size_t htmc_strdup(HtmcAllocations *ha, size_t str_idx)
{
    size_t len = ha->sizes[ str_idx ];
    size_t unused_buffer_idx = htmc_get_unused(ha, len + 1);
    char **dup = &ha->buffers[ unused_buffer_idx ];
    
    memcpy(*dup, ha->buffers[ str_idx ], len + 1);
    
    ha->sizes[ unused_buffer_idx ] = len;
    
    return unused_buffer_idx;
}

char *htmc_get_strdup(HtmcAllocations *ha, size_t str_idx)
{
    size_t idx = htmc_strdup(ha, str_idx);
    return ha->buffers[ idx ];
}

char *htmc_fmt_(HtmcAllocations *ha, const char *fmt, ...)
{
    va_list args;
    va_start(args, fmt);
    
    va_list args_copy1;
    va_copy(args_copy1, args);
    
    int str_len = vsnprintf(NULL, 0, fmt, args);
    va_end(args);
    
    size_t unused_buffer_idx = htmc_get_unused(ha, str_len + 1);
    char **unused = &ha->buffers[ unused_buffer_idx ];
    ha->sizes[ unused_buffer_idx ] = str_len;
    
    vsnprintf(*unused, str_len + 1, fmt, args_copy1);
    va_end(args_copy1);
    
    htmc_set_unused_if_alloced(ha, fmt);
    
    return *unused;
}

// TODO check in the string for '--' and report error
char *htmc_comment_(HtmcAllocations *ha, size_t str_idx)
{
    const size_t between_len = ha->sizes[ str_idx ];
    char **buffer_ptr = &ha->buffers[ str_idx ];
    size_t *cap = &ha->caps[ str_idx ];
    const size_t comment_start_len = 4;
    const size_t comment_end_len = 3;
    const size_t needed_cap = comment_start_len + between_len + comment_end_len + 1;
    
    htmc_gurantee_cap(buffer_ptr, cap, needed_cap);
    
    memmove(*buffer_ptr + comment_start_len, *buffer_ptr, between_len);
    
    memcpy(*buffer_ptr, "<!--", comment_start_len);
    memcpy(*buffer_ptr + comment_start_len + between_len, "-->", 3);
    (*buffer_ptr)[ needed_cap - 1 ] = '\0';
    
    ha->sizes[ str_idx ] = needed_cap - 1;
    
    return *buffer_ptr;
}

void htmc_gurantee_cap(char **buffer, size_t *cap, size_t new_cap)
{
    if(*cap < new_cap)
    {
        *buffer = realloc(*buffer, new_cap);
        *cap = new_cap;
    }
}

#endif /* HTMC_C */
