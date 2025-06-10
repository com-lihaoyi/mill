# Mill target completion for Bash and Zsh, with trailing space suppression

# ---- Shared logic (used by both Bash and Zsh) ----
_mill_common() {
  local input="$1"
  local cur base completions

  # Strip trailing alphanumerics from current input
  cur="${input%%[[:alnum:]]#}"
  local last_char="${cur: -1}"

  if [[ "$last_char" == "." ]]; then
    completions=$(./mill --disable-ticker resolve "${cur}_")
  else
    completions=$(./mill --disable-ticker resolve _)
  fi

  printf '%s\n' "$completions"
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