_mill_bash() {
  local completions=$(${COMP_WORDS[0]} --tabcomplete "$COMP_CWORD" $COMP_WORDS)
  COMPREPLY=( $(compgen -W "$completions" -- "$cur") )
  compopt -o nospace 2>/dev/null
}

_mill_zsh() {
  local completions=$($words[1] --tabcomplete "$CURRENT" $words)
  local -a suggestions=(${(f)completions})
  # `-S` to avoid the trailing space after a completion, since it is
  # common that the user will want to put a `.` and continue typing
  compadd -S '' -- $suggestions
}

if [ -n "${ZSH_VERSION:-}" ]; then
  autoload -Uz compinit
  compinit
  compdef _mill_zsh mill
elif [ -n "${BASH_VERSION:-}" ]; then
  complete -F _mill_bash mill
fi