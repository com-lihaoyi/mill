#!/bin/bash

DESIRED_VERSION="3.8.20"
PREV_FILE="./previous-py.txt"
PYENV_ROOT="$HOME/.pyenv"
PYENV_BIN="$PYENV_ROOT/bin/pyenv"

# Function to check if pyenv is functional using the full path
is_pyenv_functional() {
  if [ -x "$PYENV_BIN" ]; then
    # Test if pyenv can list versions (basic functionality check)
    if "$PYENV_BIN" versions --bare >/dev/null 2>&1; then
      return 0 # pyenv is functional
    fi
  fi
  return 1 # pyenv is not functional
}

# Function to add pyenv to the shell configuration
add_pyenv_to_shell() {
  echo "Adding pyenv to the shell configuration..."
  export PYENV_ROOT="$HOME/.pyenv"
  export PATH="$PYENV_ROOT/bin:$PATH"
  eval "$(pyenv init --path)"
  eval "$(pyenv init -)"
  echo "pyenv added to the shell configuration."
}

# Function to install pyenv if not already installed or functional
install_pyenv() {
  if ! is_pyenv_functional; then
    echo "pyenv is not functional. Attempting to fix..."

    # If pyenv exists but is not in PATH, try adding it to the shell configuration
    if [ -x "$PYENV_BIN" ]; then
      echo "pyenv found at $PYENV_BIN. Adding to shell configuration..."
      add_pyenv_to_shell

      # Re-check if pyenv is now functional
      if is_pyenv_functional; then
        echo "pyenv is now functional."
        return 0
      else
        echo "pyenv is still not functional after adding to shell configuration."
      fi
    fi

    # If pyenv is still not functional, remove the directory and reinstall
    echo "pyenv is not functional and cannot be fixed. Reinstalling pyenv..."
    echo "Removing existing $PYENV_ROOT directory..."
    rm -rf "$PYENV_ROOT" || { echo "Failed to remove $PYENV_ROOT directory."; exit 1; }

    echo "Installing pyenv..."
    curl https://pyenv.run | bash || { echo "Failed to install pyenv."; exit 1; }

    # Add pyenv to the shell configuration
    add_pyenv_to_shell

    # Verify pyenv is functional
    if is_pyenv_functional; then
      echo "pyenv installed successfully and is now functional."
    else
      echo "pyenv installation failed or is not functional."
      exit 1
    fi
  else
    echo "pyenv is already installed and functional."
  fi
}

usage() {
  echo "Usage: $0 -i   (Install Python ${DESIRED_VERSION} if not already active)"
  echo "       $0 -u   (Revert to the previously active Python version)"
  exit 1
}

# Check for exactly one argument.
if [ "$#" -ne 1 ]; then
  usage
fi

# Ensure pyenv is installed and functional
install_pyenv

case "$1" in
  -i)
    # Get current Python version from 'python3'
    CURRENT_VERSION=$(python3 --version 2>&1 | awk '{print $2}')
    if [ "$CURRENT_VERSION" == "$DESIRED_VERSION" ]; then
      echo "Python is already at version ${DESIRED_VERSION}"
      exit 0
    fi

    # Save the current version in previous-py.txt
    echo "$CURRENT_VERSION" > "$PREV_FILE"
    echo "Saved current Python version ($CURRENT_VERSION) to ${PREV_FILE}"

    echo "Installing Python ${DESIRED_VERSION}..."
    # Check if desired version is already installed in pyenv
    if ! pyenv versions --bare | grep -q "^${DESIRED_VERSION}\$"; then
      pyenv install "${DESIRED_VERSION}" || { echo "pyenv install failed."; exit 1; }
    fi
    pyenv global "${DESIRED_VERSION}"

    NEW_VERSION=$(python3 --version 2>&1 | awk '{print $2}')
    if [ "$NEW_VERSION" == "$DESIRED_VERSION" ]; then
      echo "Python successfully set to ${DESIRED_VERSION}"
    else
      echo "Failed to set Python version. Current version is ${NEW_VERSION}"
      exit 1
    fi
    ;;
  -u)
    # Uninstall mode: revert to the previously saved Python version.
    if [ ! -f "$PREV_FILE" ]; then
      echo "No previous Python version recorded in ${PREV_FILE}. Nothing to revert :)"
      exit 0
    fi
    PREV_VERSION=$(cat "$PREV_FILE")
    echo "Reverting to previous Python version: ${PREV_VERSION}"

    if ! pyenv versions --bare | grep -q "^${PREV_VERSION}\$"; then
      pyenv install "${PREV_VERSION}" || { echo "Failed to install Python ${PREV_VERSION}."; exit 1; }
    fi
    pyenv global "${PREV_VERSION}"

    # Optionally remove the previous version record.
    rm -f "$PREV_FILE"
    NEW_VERSION=$(python3 --version 2>&1 | awk '{print $2}')
    if [ "$NEW_VERSION" == "$PREV_VERSION" ]; then
      echo "Successfully reverted to Python ${PREV_VERSION}"
    else
      echo "Failed to revert. Current version is ${NEW_VERSION}"
      exit 1
    fi
    ;;
  *)
    usage
    ;;
esac