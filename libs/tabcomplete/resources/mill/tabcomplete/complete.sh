_mill_trim_line() {
  local line="$1"
  local ellipsis="..."

  if (( ${#line} > COLUMNS )); then
    echo "${line:0:$(( COLUMNS - ${#ellipsis} ))}${ellipsis}"
  else
    echo "$line"
  fi
}

_mill_bash() {
  compopt -o nospace 2>/dev/null
  local IFS=$'\n'

  # in bash, keep $COLUMNS up-to-date; in zsh it’s automatic
  shopt -s checkwinsize 2>/dev/null

  # grab raw, newline-split completions
  local raw=( $("${COMP_WORDS[0]}" --tab-complete "$COMP_CWORD" "${COMP_WORDS[@]}") )
  local trimmed=()

  # trim each one
  for line in "${raw[@]}"; do
    trimmed+=( "$(_mill_trim_line "$line")" )
  done

  COMPREPLY=( "${trimmed[@]}" )
}

_mill_zsh() {
  local -a raw opts trimmed
  raw=("${(f)$($words[1] --tab-complete "$((CURRENT - 1))" $words)}")

  for d in $raw; do
    opts+=( "${d%% *}" )             # value
    trimmed+=( "$(_mill_trim_line "$d")" )  # trimmed “value description…”
  done

  compadd -S '' -d trimmed -- $opts
}

if [ -n "${ZSH_VERSION:-}" ]; then
  autoload -Uz compinit
  compinit
  compdef _mill_zsh mill
elif [ -n "${BASH_VERSION:-}" ]; then
  complete -F _mill_bash mill
fi
