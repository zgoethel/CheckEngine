#include "glfw_context.h"

#include <thread>

#include "util/intrinsics/singleton.h"

void destroy_glfw_window::operator() (GLFWwindow* ptr)
{
	// Relinquish the context from the current thread
	glfwMakeContextCurrent(NULL);

	// Schedule a main-thread task to destroy the context
	(global_glfw_context::instance->thread_queue).add(
		new std::function<bool()>([ptr]() -> bool
		{
			glfwDestroyWindow(ptr);
		
			// Return true to indicate this lambda should be deleted once executed
			return true;
		}));

	glfwPostEmptyEvent();
}


global_glfw_context::global_glfw_context()
{
	if (glfwInit() != GLFW_TRUE)
	{
		glfwTerminate();

		throw std::exception("GLFW failed to initialize; check system requirements and libraries");
	}
}

global_glfw_context::~global_glfw_context()
{
	glfwTerminate();
}

void global_glfw_context::execute_queue()
{
	while ((this->thread_queue).get_size() > 0)
	{
		std::function<bool()> *first = (this->thread_queue).remove();

		// If the lambda returns true, delete once executed
		if ((*first)())
			delete first;
	}
}

void global_glfw_context::park_thread()
{
	while (active_contexts > 0)
	{
		glfwWaitEventsTimeout(0.1);

		execute_queue();
	}

	execute_queue();
}

// A global singleton instance of this context for all threads
std::shared_ptr<global_glfw_context> global_glfw_context::instance = per_thread<global_glfw_context>::get_or_create();


glfw_context::glfw_context(int context_version)
{
	glfwDefaultWindowHints();

	glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, context_version / 10);
	glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, context_version % 10);

	glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);

	this->pointer.reset(glfwCreateWindow(1366, 910, "", NULL, NULL));
	this->make_current();

	if (glewInit() != GLEW_OK)
	{
		glfwTerminate();

		throw std::exception("GLEW failed to initialize; check system requirements and libraries");
	}

	// Relinquish all contexts on the main thread
	glfwMakeContextCurrent(NULL);

	global_glfw_context::instance->active_contexts++;
}

glfw_context::~glfw_context()
{
	global_glfw_context::instance->active_contexts--;
}

void glfw_context::make_current()
{
	glfwMakeContextCurrent(this->pointer.get());
}

bootable_game::bootable_game(std::function<void()> temp_start, std::function<void()> temp_update)
{
	this->context.reset(new glfw_context(33));

	this->temp_start = temp_start;
	this->temp_update = temp_update;
}

void bootable_game::park_thread()
{
	per_thread<glfw_context>::set(this->context);
	per_thread<glfw_context>::get_or_create()->make_current();

	auto window = per_thread<glfw_window>::get_or_create();
	//per_thread<glfw_keyboard>::get_or_create();
	//per_thread<glfw_mouse>::get_or_create();

	temp_start();

	per_thread<glfw_window>::get_or_create()->show();

	while (glfwWindowShouldClose(this->context->pointer.get()) != GLFW_TRUE)
	{
		temp_update();

		window->swap_buffers();
	}

	per_thread<glfw_window>::remove_reference();
	//per_thread<glfw_keyboard>::remove_reference();
	//per_thread<glfw_mouse>::remove_reference();

	per_thread<glfw_window>::remove_reference();
}

void bootable_game::boot_thread()
{
	std::thread(&bootable_game::park_thread, *this).detach();
}