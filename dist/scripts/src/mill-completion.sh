# ---- Shared logic (used by both Bash and Zsh) ----
_mill_common() {
  local input="$1"
  local cur
  local cmd

  # Strip trailing alphanumerics from current input
  cur=$(echo "$input" | sed 's/[[:alnum:]][[:alnum:]]*$//')

  if [ -n "${ZSH_VERSION:-}" ]; then
    cmd="$words[1]"
  elif [ -n "${BASH_VERSION:-}" ]; then
    cmd="${COMP_WORDS[0]}"
  fi
  # Disable the ticker since it gets in the way all the time, but leave
  # stderr un-redirected so that if the completion is blocked on build
  # compilation or something at least the user has some logs indicating
  # something is going on
  $cmd --ticker false resolve "${cur}_"
}

# ---- Bash-specific function ----
_mill_bash() {
  local cur="${COMP_WORDS[COMP_CWORD]}"
  local completions=$(_mill_common "$cur")
  COMPREPLY=( $(compgen -W "$completions" -- "$cur") )
  compopt -o nospace 2>/dev/null
}

# ---- Zsh-specific function ----
_mill_zsh() {
  local cur="${words[CURRENT]}"
  local completions
  completions=$(_mill_common "$cur")
  local -a suggestions
  suggestions=(${(f)completions})
  # `-S` to avoid the trailing space after a completion, since it is
  # common that the user will want to put a `.` and continue typing
  compadd -S '' -- $suggestions
}

# ---- Register appropriate completion ----
if [ -n "${ZSH_VERSION:-}" ]; then
  autoload -Uz compinit
  compinit
  compdef _mill_zsh mill
elif [ -n "${BASH_VERSION:-}" ]; then
  complete -F _mill_bash mill
fi