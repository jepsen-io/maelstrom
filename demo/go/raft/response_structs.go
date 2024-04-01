package main

type ResponseMsg struct {
	Id   int                    `json:"id"`
	Src  string                 `json:"src,omitempty"`
	Dest string                 `json:"dest,omitempty"`
	Body map[string]interface{} `json:"body,omitempty"`
}
