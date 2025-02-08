#!/bin/bash

DESIRED_VERSION="3.8.20"
PREV_FILE="./previous-py.txt"

# Function to install pyenv if not already installed
install_pyenv() {
  if ! command -v pyenv >/dev/null 2>&1; then
    echo "pyenv is not installed. Installing pyenv..."
    curl https://pyenv.run | bash || { echo "Failed to install pyenv."; exit 1; }

    # Add pyenv to the shell configuration
    export PYENV_ROOT="$HOME/.pyenv"
    export PATH="$PYENV_ROOT/bin:$PATH"
    eval "$(pyenv init --path)"
    eval "$(pyenv init -)"

    echo "pyenv installed successfully."
  else
    echo "pyenv is already installed."
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

# Ensure pyenv is installed
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