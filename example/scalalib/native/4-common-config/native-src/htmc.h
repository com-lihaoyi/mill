#ifndef HTMC_H
#define HTMC_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

typedef struct
{
    size_t nb;
    bool *unused;
    size_t *caps;
    size_t *sizes;
    char **buffers;
} HtmcAllocations;

typedef struct
{
    char **arr;
    size_t nb;
} HtmcStrsArr;

#define htmc(...) \
({ \
    const size_t init_cap = 4; \
    HtmcAllocations htmc_ha = { \
        .nb = init_cap, \
        .buffers = calloc(init_cap, sizeof(char*)), \
        .caps = calloc(init_cap, sizeof(size_t)), \
        .sizes = calloc(init_cap, sizeof(size_t)), \
        .unused = malloc(init_cap * sizeof(bool)), \
    }; \
    memset(htmc_ha.unused, 1, init_cap * sizeof(bool)); \
    size_t ret_idx = htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)); \
    char *ret = htmc_ha.buffers[ ret_idx ]; \
    htmc_cleanup_unused_buffers(&htmc_ha, ret_idx); \
    ret; \
})

#define htmc_strsarr(...) (HtmcStrsArr){.arr=(char*[]){__VA_ARGS__}, .nb=sizeof((char*[]){__VA_ARGS__}) / sizeof(char*)}

#define htmc_repeat(nb, ...) htmc_repeat_(&htmc_ha, nb, htmc_strsarr(__VA_ARGS__))
#define htmc_repeat_modify(nb, mod, ...) htmc_repeat_modify_(&htmc_ha, nb, mod, htmc_strsarr(__VA_ARGS__))
#define htmc_repeat_modify_r(nb, mod, ctx, ...) htmc_repeat_modify_r_(&htmc_ha, nb, mod, ctx, htmc_strsarr(__VA_ARGS__))

#define htmc_fmt(fmt, ...) htmc_fmt_(&htmc_ha, fmt, ##__VA_ARGS__)

#define htmc_ccode(...) \
({ \
    size_t htmc_ccode_yielded_idx = htmc_get_unused(&htmc_ha, 16); \
    htmc_ha.sizes[ htmc_ccode_yielded_idx ] = 0; \
    htmc_ha.buffers[ htmc_ccode_yielded_idx ][0] = '\0'; \
    __VA_ARGS__ \
    char *htmc_ccode_yielded = htmc_ha.buffers[ htmc_ccode_yielded_idx ]; \
    htmc_ccode_yielded; \
})
#define htmc_yield(...) htmc_append_to_buffer_idx(&htmc_ha, htmc_ccode_yielded_idx, htmc_strsarr(__VA_ARGS__))
#define htmc_yielded htmc_ha.buffers[ htmc_ccode_yielded_idx ]
#define htmc_yielded_len htmc_ha.sizes[ htmc_ccode_yielded_idx ]

#define htmc_attr_(...) \
htmc_strsarr(__VA_ARGS__)))

#define htmc_attr(tag, ...) \
_Generic(&(char[htmc_is_single_tag(htmc_id_##tag) + 1]){ 0 } , \
    char(*)[1]: htmc_surround_by_tag_with_attrs, \
    char(*)[2]: htmc_make_tag_with_attrs \
)(&htmc_ha, htmc_id_##tag, htmc_strsarr(__VA_ARGS__), htmc_concat_strings(&htmc_ha, htmc_attr_

#define htmc_strlit(...) #__VA_ARGS__

#define htmc_is_single_tag(id) \
(id == (htmc_id_area || htmc_id_base || htmc_id_br || htmc_id_col || htmc_id_embed || htmc_id_hr || htmc_id_img || htmc_id_input || htmc_id_link || htmc_id_meta || htmc_id_param || htmc_id_source || htmc_id_track || htmc_id_wbr))

#define htmc_doctypehtml ("<!DOCTYPE html>")

#define htmc_comment(...) htmc_comment_(&htmc_ha, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))

// tags that need a closing tag:
#define htmc_a(...) htmc_surround_by_tag(&htmc_ha, 0, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_abbr(...) htmc_surround_by_tag(&htmc_ha, 1, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_address(...) htmc_surround_by_tag(&htmc_ha, 2, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_article(...) htmc_surround_by_tag(&htmc_ha, 4, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_aside(...) htmc_surround_by_tag(&htmc_ha, 5, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_audio(...) htmc_surround_by_tag(&htmc_ha, 6, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_b(...) htmc_surround_by_tag(&htmc_ha, 7, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_bdi(...) htmc_surround_by_tag(&htmc_ha, 9, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_bdo(...) htmc_surround_by_tag(&htmc_ha, 10, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_blockquote(...) htmc_surround_by_tag(&htmc_ha, 11, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_body(...) htmc_surround_by_tag(&htmc_ha, 12, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_button(...) htmc_surround_by_tag(&htmc_ha, 14, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_canvas(...) htmc_surround_by_tag(&htmc_ha, 15, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_caption(...) htmc_surround_by_tag(&htmc_ha, 16, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_cite(...) htmc_surround_by_tag(&htmc_ha, 17, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_code(...) htmc_surround_by_tag(&htmc_ha, 18, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_colgroup(...) htmc_surround_by_tag(&htmc_ha, 20, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_data(...) htmc_surround_by_tag(&htmc_ha, 21, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_datalist(...) htmc_surround_by_tag(&htmc_ha, 22, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_dd(...) htmc_surround_by_tag(&htmc_ha, 23, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_del(...) htmc_surround_by_tag(&htmc_ha, 24, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_details(...) htmc_surround_by_tag(&htmc_ha, 25, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_dfn(...) htmc_surround_by_tag(&htmc_ha, 26, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_dialog(...) htmc_surround_by_tag(&htmc_ha, 27, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_div(...) htmc_surround_by_tag(&htmc_ha, 28, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_dl(...) htmc_surround_by_tag(&htmc_ha, 29, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_dt(...) htmc_surround_by_tag(&htmc_ha, 30, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_em(...) htmc_surround_by_tag(&htmc_ha, 31, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_fieldset(...) htmc_surround_by_tag(&htmc_ha, 33, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_figcaption(...) htmc_surround_by_tag(&htmc_ha, 34, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_figure(...) htmc_surround_by_tag(&htmc_ha, 35, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_footer(...) htmc_surround_by_tag(&htmc_ha, 36, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_form(...) htmc_surround_by_tag(&htmc_ha, 37, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_h1(...) htmc_surround_by_tag(&htmc_ha, 38, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_h2(...) htmc_surround_by_tag(&htmc_ha, 39, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_h3(...) htmc_surround_by_tag(&htmc_ha, 40, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_h4(...) htmc_surround_by_tag(&htmc_ha, 41, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_h5(...) htmc_surround_by_tag(&htmc_ha, 42, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_h6(...) htmc_surround_by_tag(&htmc_ha, 43, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_head(...) htmc_surround_by_tag(&htmc_ha, 44, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_header(...) htmc_surround_by_tag(&htmc_ha, 45, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_hgroup(...) htmc_surround_by_tag(&htmc_ha, 46, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_html(...) htmc_surround_by_tag(&htmc_ha, 48, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_i(...) htmc_surround_by_tag(&htmc_ha, 49, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_iframe(...) htmc_surround_by_tag(&htmc_ha, 50, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_ins(...) htmc_surround_by_tag(&htmc_ha, 53, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_kbd(...) htmc_surround_by_tag(&htmc_ha, 54, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_label(...) htmc_surround_by_tag(&htmc_ha, 55, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_legend(...) htmc_surround_by_tag(&htmc_ha, 56, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_li(...) htmc_surround_by_tag(&htmc_ha, 57, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_main(...) htmc_surround_by_tag(&htmc_ha, 59, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_map(...) htmc_surround_by_tag(&htmc_ha, 60, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_mark(...) htmc_surround_by_tag(&htmc_ha, 61, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_math(...) htmc_surround_by_tag(&htmc_ha, 62, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_menu(...) htmc_surround_by_tag(&htmc_ha, 63, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_meter(...) htmc_surround_by_tag(&htmc_ha, 65, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_nav(...) htmc_surround_by_tag(&htmc_ha, 66, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_noscript(...) htmc_surround_by_tag(&htmc_ha, 67, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_object(...) htmc_surround_by_tag(&htmc_ha, 68, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_ol(...) htmc_surround_by_tag(&htmc_ha, 69, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_optgroup(...) htmc_surround_by_tag(&htmc_ha, 70, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_option(...) htmc_surround_by_tag(&htmc_ha, 71, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_output(...) htmc_surround_by_tag(&htmc_ha, 72, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_p(...) htmc_surround_by_tag(&htmc_ha, 73, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_picture(...) htmc_surround_by_tag(&htmc_ha, 75, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_pre(...) htmc_surround_by_tag(&htmc_ha, 76, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_progress(...) htmc_surround_by_tag(&htmc_ha, 77, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_q(...) htmc_surround_by_tag(&htmc_ha, 78, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_rp(...) htmc_surround_by_tag(&htmc_ha, 79, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_rt(...) htmc_surround_by_tag(&htmc_ha, 80, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_ruby(...) htmc_surround_by_tag(&htmc_ha, 81, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_s(...) htmc_surround_by_tag(&htmc_ha, 82, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_samp(...) htmc_surround_by_tag(&htmc_ha, 83, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_script(...) htmc_surround_by_tag(&htmc_ha, 84, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_section(...) htmc_surround_by_tag(&htmc_ha, 85, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_select(...) htmc_surround_by_tag(&htmc_ha, 86, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_slot(...) htmc_surround_by_tag(&htmc_ha, 87, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_small(...) htmc_surround_by_tag(&htmc_ha, 88, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_span(...) htmc_surround_by_tag(&htmc_ha, 90, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_strong(...) htmc_surround_by_tag(&htmc_ha, 91, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_style(...) htmc_surround_by_tag(&htmc_ha, 92, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_sub(...) htmc_surround_by_tag(&htmc_ha, 93, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_summary(...) htmc_surround_by_tag(&htmc_ha, 94, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_sup(...) htmc_surround_by_tag(&htmc_ha, 95, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_svg(...) htmc_surround_by_tag(&htmc_ha, 96, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_table(...) htmc_surround_by_tag(&htmc_ha, 97, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_tbody(...) htmc_surround_by_tag(&htmc_ha, 98, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_td(...) htmc_surround_by_tag(&htmc_ha, 99, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_template(...) htmc_surround_by_tag(&htmc_ha, 100, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_textarea(...) htmc_surround_by_tag(&htmc_ha, 101, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_tfoot(...) htmc_surround_by_tag(&htmc_ha, 102, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_th(...) htmc_surround_by_tag(&htmc_ha, 103, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_thead(...) htmc_surround_by_tag(&htmc_ha, 104, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_time(...) htmc_surround_by_tag(&htmc_ha, 105, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_title(...) htmc_surround_by_tag(&htmc_ha, 106, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_tr(...) htmc_surround_by_tag(&htmc_ha, 107, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_u(...) htmc_surround_by_tag(&htmc_ha, 109, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_ul(...) htmc_surround_by_tag(&htmc_ha, 110, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_var(...) htmc_surround_by_tag(&htmc_ha, 111, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))
#define htmc_video(...) htmc_surround_by_tag(&htmc_ha, 112, htmc_concat_strings(&htmc_ha, htmc_strsarr(__VA_ARGS__)))

// tags that don't terminate
#define htmc_area(...) htmc_make_tag_with_attrs(&htmc_ha, 3, htmc_strsarr(__VA_ARGS__), NULL)
#define htmc_base(...) htmc_make_tag_with_attrs(&htmc_ha, 8, htmc_strsarr(__VA_ARGS__), NULL)
#define htmc_br(...) htmc_make_tag_with_attrs(&htmc_ha, 13, htmc_strsarr(__VA_ARGS__), NULL)
#define htmc_col(...) htmc_make_tag_with_attrs(&htmc_ha, 19, htmc_strsarr(__VA_ARGS__), NULL)
#define htmc_embed(...) htmc_make_tag_with_attrs(&htmc_ha, 32, htmc_strsarr(__VA_ARGS__), NULL)
#define htmc_hr(...) htmc_make_tag_with_attrs(&htmc_ha, 47, htmc_strsarr(__VA_ARGS__), NULL)
#define htmc_img(...) htmc_make_tag_with_attrs(&htmc_ha, 51, htmc_strsarr(__VA_ARGS__), NULL)
#define htmc_input(...) htmc_make_tag_with_attrs(&htmc_ha, 52, htmc_strsarr(__VA_ARGS__), NULL)
#define htmc_link(...) htmc_make_tag_with_attrs(&htmc_ha, 58, htmc_strsarr(__VA_ARGS__), NULL)
#define htmc_meta(...) htmc_make_tag_with_attrs(&htmc_ha, 64, htmc_strsarr(__VA_ARGS__), NULL)
#define htmc_param(...) htmc_make_tag_with_attrs(&htmc_ha, 74, htmc_strsarr(__VA_ARGS__), NULL)
#define htmc_source(...) htmc_make_tag_with_attrs(&htmc_ha, 89, htmc_strsarr(__VA_ARGS__), NULL)
#define htmc_track(...) htmc_make_tag_with_attrs(&htmc_ha, 108, htmc_strsarr(__VA_ARGS__), NULL)
#define htmc_wbr(...) htmc_make_tag_with_attrs(&htmc_ha, 113, htmc_strsarr(__VA_ARGS__), NULL)

#define htmc_id_a 0
#define htmc_id_abbr 1
#define htmc_id_address 2
#define htmc_id_area 3
#define htmc_id_article 4
#define htmc_id_aside 5
#define htmc_id_audio 6
#define htmc_id_b 7
#define htmc_id_base 8
#define htmc_id_bdi 9
#define htmc_id_bdo 10
#define htmc_id_blockquote 11
#define htmc_id_body 12
#define htmc_id_br 13
#define htmc_id_button 14
#define htmc_id_canvas 15
#define htmc_id_caption 16
#define htmc_id_cite 17
#define htmc_id_code 18
#define htmc_id_col 19
#define htmc_id_colgroup 20
#define htmc_id_data 21
#define htmc_id_datalist 22
#define htmc_id_dd 23
#define htmc_id_del 24
#define htmc_id_details 25
#define htmc_id_dfn 26
#define htmc_id_dialog 27
#define htmc_id_div 28
#define htmc_id_dl 29
#define htmc_id_dt 30
#define htmc_id_em 31
#define htmc_id_embed 32
#define htmc_id_fieldset 33
#define htmc_id_figcaption 34
#define htmc_id_figure 35
#define htmc_id_footer 36
#define htmc_id_form 37
#define htmc_id_h1 38
#define htmc_id_h2 39
#define htmc_id_h3 40
#define htmc_id_h4 41
#define htmc_id_h5 42
#define htmc_id_h6 43
#define htmc_id_head 44
#define htmc_id_header 45
#define htmc_id_hgroup 46
#define htmc_id_hr 47
#define htmc_id_html 48
#define htmc_id_i 49
#define htmc_id_iframe 50
#define htmc_id_img 51
#define htmc_id_input 52
#define htmc_id_ins 53
#define htmc_id_kbd 54
#define htmc_id_label 55
#define htmc_id_legend 56
#define htmc_id_li 57
#define htmc_id_link 58
#define htmc_id_main 59
#define htmc_id_map 60
#define htmc_id_mark 61
#define htmc_id_math 62
#define htmc_id_menu 63
#define htmc_id_meta 64
#define htmc_id_meter 65
#define htmc_id_nav 66
#define htmc_id_noscript 67
#define htmc_id_object 68
#define htmc_id_ol 69
#define htmc_id_optgroup 70
#define htmc_id_option 71
#define htmc_id_output 72
#define htmc_id_p 73
#define htmc_id_param 74
#define htmc_id_picture 75
#define htmc_id_pre 76
#define htmc_id_progress 77
#define htmc_id_q 78
#define htmc_id_rp 79
#define htmc_id_rt 80
#define htmc_id_ruby 81
#define htmc_id_s 82
#define htmc_id_samp 83
#define htmc_id_script 84
#define htmc_id_section 85
#define htmc_id_select 86
#define htmc_id_slot 87
#define htmc_id_small 88
#define htmc_id_source 89
#define htmc_id_span 90
#define htmc_id_strong 91
#define htmc_id_style 92
#define htmc_id_sub 93
#define htmc_id_summary 94
#define htmc_id_sup 95
#define htmc_id_svg 96
#define htmc_id_table 97
#define htmc_id_tbody 98
#define htmc_id_td 99
#define htmc_id_template 100
#define htmc_id_textarea 101
#define htmc_id_tfoot 102
#define htmc_id_th 103
#define htmc_id_thead 104
#define htmc_id_time 105
#define htmc_id_title 106
#define htmc_id_tr 107
#define htmc_id_track 108
#define htmc_id_u 109
#define htmc_id_ul 110
#define htmc_id_var 111
#define htmc_id_video 112
#define htmc_id_wbr 113

void htmc_cleanup_unused_buffers(HtmcAllocations *ha, size_t used_idx);
size_t htmc_find_buffer(const HtmcAllocations *ha, const char *buffer);
void htmc_grow_buffers(HtmcAllocations *ha);
void htmc_set_unused(HtmcAllocations *ha, const char *str);
void htmc_set_unused_if_alloced(HtmcAllocations *ha, const char *str);
size_t htmc_find_unused(const HtmcAllocations *ha);
size_t htmc_get_unused(HtmcAllocations *ha, size_t with_size);
size_t htmc_concat_strings(HtmcAllocations *ha, HtmcStrsArr strs);
char *htmc_surround_by_tag(HtmcAllocations *ha, uint16_t tag_id, size_t str_idx);
char *htmc_surround_by_tag_with_attrs(HtmcAllocations *ha, uint16_t tag_id, HtmcStrsArr attrs, size_t str_idx);
char *htmc_make_tag(HtmcAllocations *ha, uint16_t tag_id);
char *htmc_make_tag_with_attrs(HtmcAllocations *ha, uint16_t tag_id, HtmcStrsArr attrs, char *dummy);
char *htmc_repeat_(HtmcAllocations *ha, uint32_t nb, HtmcStrsArr strs);
char *htmc_repeat_modify_(HtmcAllocations *ha, uint32_t nb, void(*mod)(const char *before_mod, size_t len, char **buffer, size_t *cap, uint32_t idx), HtmcStrsArr strs);
char *htmc_repeat_modify_r_(HtmcAllocations *ha, uint32_t nb, void(*mod)(const char *before_mod, size_t len, char **buffer, size_t *cap, uint32_t idx, void *arg), void *arg, HtmcStrsArr strs);
char *htmc_fmt_(HtmcAllocations *ha, const char *fmt, ...);
void htmc_append_to_buffer_idx(HtmcAllocations *ha, size_t buffer_idx, HtmcStrsArr strs);
size_t htmc_strdup(HtmcAllocations *ha, size_t str_idx);
char *htmc_get_strdup(HtmcAllocations *ha, size_t str_idx);
char *htmc_comment_(HtmcAllocations *ha, size_t str_idx);

void htmc_gurantee_cap(char **buffer, size_t *cap, size_t new_cap);

#endif

#ifdef HTMC_PREFIX

#undef HTMC_PREFIX

#undef doctypehtml

#undef comment

#undef a
#undef abbr
#undef address
#undef area
#undef article
#undef aside
#undef audio
#undef b
#undef base
#undef bdi
#undef bdo
#undef blockquote
#undef body
#undef br
#undef button
#undef canvas
#undef caption
#undef cite
#undef code
#undef col
#undef colgroup
#undef data
#undef datalist
#undef dd
#undef del
#undef details
#undef dfn
#undef dialog
#undef div
#undef dl
#undef dt
#undef em
#undef embed
#undef fieldset
#undef figcaption
#undef figure
#undef footer
#undef form
#undef h1
#undef h2
#undef h3
#undef h4
#undef h5
#undef h6
#undef head
#undef header
#undef hgroup
#undef hr
#undef html
#undef i
#undef iframe
#undef img
#undef input
#undef ins
#undef kbd
#undef label
#undef legend
#undef li
#undef link
#undef map
#undef mark
#undef math
#undef menu
#undef meta
#undef meter
#undef nav
#undef noscript
#undef object
#undef ol
#undef optgroup
#undef option
#undef output
#undef p
#undef param
#undef picture
#undef pre
#undef progress
#undef q
#undef rp
#undef rt
#undef ruby
#undef s
#undef samp
#undef script
#undef section
#undef select
#undef slot
#undef small
#undef source
#undef span
#undef strong
#undef style
#undef sub
#undef summary
#undef sup
#undef svg
#undef table
#undef tbody
#undef td
#undef template
#undef textarea
#undef tfoot
#undef th
#undef thead
#undef time
#undef title
#undef tr
#undef track
#undef u
#undef ul
#undef var
#undef video
#undef wbr

#undef attr

#else

#define doctypehtml htmc_doctypehtml

#define comment htmc_comment

#define a(...) htmc_a(__VA_ARGS__)
#define abbr(...) htmc_abbr(__VA_ARGS__)
#define address(...) htmc_address(__VA_ARGS__)
#define area(...) htmc_area(__VA_ARGS__)
#define article(...) htmc_article(__VA_ARGS__)
#define aside(...) htmc_aside(__VA_ARGS__)
#define audio(...) htmc_audio(__VA_ARGS__)
#define b(...) htmc_b(__VA_ARGS__)
#define base(...) htmc_base(__VA_ARGS__)
#define bdi(...) htmc_bdi(__VA_ARGS__)
#define bdo(...) htmc_bdo(__VA_ARGS__)
#define blockquote(...) htmc_blockquote(__VA_ARGS__)
#define body(...) htmc_body(__VA_ARGS__)
#define br(...) htmc_br(__VA_ARGS__)
#define button(...) htmc_button(__VA_ARGS__)
#define canvas(...) htmc_canvas(__VA_ARGS__)
#define caption(...) htmc_caption(__VA_ARGS__)
#define cite(...) htmc_cite(__VA_ARGS__)
#define code(...) htmc_code(__VA_ARGS__)
#define col(...) htmc_col(__VA_ARGS__)
#define colgroup(...) htmc_colgroup(__VA_ARGS__)
#define data(...) htmc_data(__VA_ARGS__)
#define datalist(...) htmc_datalist(__VA_ARGS__)
#define dd(...) htmc_dd(__VA_ARGS__)
#define del(...) htmc_del(__VA_ARGS__)
#define details(...) htmc_details(__VA_ARGS__)
#define dfn(...) htmc_dfn(__VA_ARGS__)
#define dialog(...) htmc_dialog(__VA_ARGS__)
#define div(...) htmc_div(__VA_ARGS__)
#define dl(...) htmc_dl(__VA_ARGS__)
#define dt(...) htmc_dt(__VA_ARGS__)
#define em(...) htmc_em(__VA_ARGS__)
#define embed(...) htmc_embed(__VA_ARGS__)
#define fieldset(...) htmc_fieldset(__VA_ARGS__)
#define figcaption(...) htmc_figcaption(__VA_ARGS__)
#define figure(...) htmc_figure(__VA_ARGS__)
#define footer(...) htmc_footer(__VA_ARGS__)
#define form(...) htmc_form(__VA_ARGS__)
#define h1(...) htmc_h1(__VA_ARGS__)
#define h2(...) htmc_h2(__VA_ARGS__)
#define h3(...) htmc_h3(__VA_ARGS__)
#define h4(...) htmc_h4(__VA_ARGS__)
#define h5(...) htmc_h5(__VA_ARGS__)
#define h6(...) htmc_h6(__VA_ARGS__)
#define head(...) htmc_head(__VA_ARGS__)
#define header(...) htmc_header(__VA_ARGS__)
#define hgroup(...) htmc_hgroup(__VA_ARGS__)
#define hr(...) htmc_hr(__VA_ARGS__)
#define html(...) htmc_html(__VA_ARGS__)
#define i(...) htmc_i(__VA_ARGS__)
#define iframe(...) htmc_iframe(__VA_ARGS__)
#define img(...) htmc_img(__VA_ARGS__)
#define input(...) htmc_input(__VA_ARGS__)
#define ins(...) htmc_ins(__VA_ARGS__)
#define kbd(...) htmc_kbd(__VA_ARGS__)
#define label(...) htmc_label(__VA_ARGS__)
#define legend(...) htmc_legend(__VA_ARGS__)
#define li(...) htmc_li(__VA_ARGS__)
#define link(...) htmc_link(__VA_ARGS__)
#define map(...) htmc_map(__VA_ARGS__)
#define mark(...) htmc_mark(__VA_ARGS__)
#define math(...) htmc_math(__VA_ARGS__)
#define menu(...) htmc_menu(__VA_ARGS__)
#define meta(...) htmc_meta(__VA_ARGS__)
#define meter(...) htmc_meter(__VA_ARGS__)
#define nav(...) htmc_nav(__VA_ARGS__)
#define noscript(...) htmc_noscript(__VA_ARGS__)
#define object(...) htmc_object(__VA_ARGS__)
#define ol(...) htmc_ol(__VA_ARGS__)
#define optgroup(...) htmc_optgroup(__VA_ARGS__)
#define option(...) htmc_option(__VA_ARGS__)
#define output(...) htmc_output(__VA_ARGS__)
#define p(...) htmc_p(__VA_ARGS__)
#define param(...) htmc_param(__VA_ARGS__)
#define picture(...) htmc_picture(__VA_ARGS__)
#define pre(...) htmc_pre(__VA_ARGS__)
#define progress(...) htmc_progress(__VA_ARGS__)
#define q(...) htmc_q(__VA_ARGS__)
#define rp(...) htmc_rp(__VA_ARGS__)
#define rt(...) htmc_rt(__VA_ARGS__)
#define ruby(...) htmc_ruby(__VA_ARGS__)
#define s(...) htmc_s(__VA_ARGS__)
#define samp(...) htmc_samp(__VA_ARGS__)
#define script(...) htmc_script(__VA_ARGS__)
#define section(...) htmc_section(__VA_ARGS__)
#define select(...) htmc_select(__VA_ARGS__)
#define slot(...) htmc_slot(__VA_ARGS__)
#define small(...) htmc_small(__VA_ARGS__)
#define source(...) htmc_source(__VA_ARGS__)
#define span(...) htmc_span(__VA_ARGS__)
#define strong(...) htmc_strong(__VA_ARGS__)
#define style(...) htmc_style(__VA_ARGS__)
#define sub(...) htmc_sub(__VA_ARGS__)
#define summary(...) htmc_summary(__VA_ARGS__)
#define sup(...) htmc_sup(__VA_ARGS__)
#define svg(...) htmc_svg(__VA_ARGS__)
#define table(...) htmc_table(__VA_ARGS__)
#define tbody(...) htmc_tbody(__VA_ARGS__)
#define td(...) htmc_td(__VA_ARGS__)
#define template(...) htmc_template(__VA_ARGS__)
#define textarea(...) htmc_textarea(__VA_ARGS__)
#define tfoot(...) htmc_tfoot(__VA_ARGS__)
#define th(...) htmc_th(__VA_ARGS__)
#define thead(...) htmc_thead(__VA_ARGS__)
#define time(...) htmc_time(__VA_ARGS__)
#define title(...) htmc_title(__VA_ARGS__)
#define tr(...) htmc_tr(__VA_ARGS__)
#define track(...) htmc_track(__VA_ARGS__)
#define u(...) htmc_u(__VA_ARGS__)
#define ul(...) htmc_ul(__VA_ARGS__)
#define var(...) htmc_var(__VA_ARGS__)
#define video(...) htmc_video(__VA_ARGS__)
#define wbr(...) htmc_wbr(__VA_ARGS__)

#define attr(...) htmc_attr(__VA_ARGS__)

#endif
