_mill_bash() {
  COMPREPLY=( $(${COMP_WORDS[0]} --tab-complete "$COMP_CWORD" $COMP_WORDS) )
  compopt -o nospace 2>/dev/null
}

_mill_zsh() {
  # `-S` to avoid the trailing space after a completion, since it is
  # common that the user will want to put a `.` and continue typing
  compadd -S '' -- $($words[1] --tab-complete "$CURRENT" $words)
}

if [ -n "${ZSH_VERSION:-}" ]; then
  autoload -Uz compinit
  compinit
  compdef _mill_zsh mill
elif [ -n "${BASH_VERSION:-}" ]; then
  complete -F _mill_bash mill
fi