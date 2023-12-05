# Compiler.
CXX = g++

# Compiler flags.
CXXFLAGS = -I. -I/opt/homebrew/Cellar/boost/1.82.0_1/include -std=c++17

# Linker flags.
LDFLAGS = -L/opt/homebrew/Cellar/boost/1.82.0_1/lib

# Debugging flag.
DEBUG ?= 0

ifeq ($(DEBUG), 1)
	CXXFLAGS += -g
endif

# Libraries.
LIBS = -lboost_json

# Source files.
SRC = maelstrom.cpp echo.cpp

# Object files.
OBJ = $(SRC:.cpp=.o)

# Target binary.
TARGET = echo

all: $(TARGET)

$(TARGET): $(OBJ)
	$(CXX) $(LDFLAGS) $(OBJ) -o $(TARGET) $(LIBS)

%.o: %.cpp
	$(CXX) $(CXXFLAGS) -c $< -o $@

clean:
	rm -f $(OBJ) $(TARGET)
