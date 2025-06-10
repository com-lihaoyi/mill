_mill_bash() {
  local cur="${COMP_WORDS[COMP_CWORD]}"
  local completions=$(${COMP_WORDS[0]} --tabcomplete "$cur")
  COMPREPLY=( $(compgen -W "$completions" -- "$cur") )
  compopt -o nospace 2>/dev/null
}

_mill_zsh() {
  local cur="${words[CURRENT]}"
  local completions
  completions=$($words[1] --tabcomplete "$cur")
  local -a suggestions
  suggestions=(${(f)completions})
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