_mill_bash() {
  # compopt makes bash not insert a newline after each completion, which
  # is what we want for modules. Only works for bash 4+
  compopt -o nospace 2>/dev/null
  COMPREPLY=( $(${COMP_WORDS[0]} --tab-complete "$COMP_CWORD" "${COMP_WORDS[@]}") )
}

_mill_zsh() {
  # `-S` to avoid the trailing space after a completion, since it is
  # common that the user will want to put a `.` and continue typing
  #
  # zsh $CURRENT is 1-indexed while bash $COMP_CWORD is 0-indexed, so
  # subtract 1 from zsh's variable so Mill gets a consistent index
  local -a descs opts
  descs=("${(f)$($words[1] --tab-complete "$((CURRENT - 1))" --is-zsh $words)}")
  for d in $descs; do
    opts+=("${d%% *}")      # before the space
  done

  # -S '' = no suffix; -d expl = descriptions array
  compadd -S '' -d descs -- $opts
}

if [ -n "${ZSH_VERSION:-}" ]; then
  autoload -Uz compinit
  compinit
  compdef _mill_zsh mill
elif [ -n "${BASH_VERSION:-}" ]; then
  complete -F _mill_bash mill
fi
