package funfit.community.post.entity;

import funfit.community.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;

@Entity
@Getter
public class Post extends BaseEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "post_id")
    private long id;

    @Column(nullable = false)
    private long userId;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String content;

    @Enumerated(value = EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    private int views;

    public static Post create(long userId, String username, String title, String content, Category category) {
        Post post = new Post();
        post.userId = userId;
        post.username = username;
        post.title = title;
        post.content = content;
        post.category = category;
        return post;
    }

    public void increaseViews() {
        this.views++;
    }
}
