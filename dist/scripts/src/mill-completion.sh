_mill_bash() {
  echo $COMP_WORDS >> ~/mill-debug-log.txt
  COMPREPLY=( $(${COMP_WORDS[0]} --tab-complete "$COMP_CWORD" "${COMP_WORDS[@]}") )
  compopt -o nospace 2>/dev/null
}

_mill_zsh() {
  # `-S` to avoid the trailing space after a completion, since it is
  # common that the user will want to put a `.` and continue typing
  #
  # zsh $CURRENT is 1-indexed while bash $COMP_CWORD is 0-indexed, so
  # subtract 1 from zsh's variable so Mill gets a consistent index
  compadd -S '' -- $($words[1] --tab-complete "$((CURRENT - 1))" $words)
}

if [ -n "${ZSH_VERSION:-}" ]; then
  autoload -Uz compinit
  compinit
  compdef _mill_zsh mill
elif [ -n "${BASH_VERSION:-}" ]; then
  complete -F _mill_bash mill
fi